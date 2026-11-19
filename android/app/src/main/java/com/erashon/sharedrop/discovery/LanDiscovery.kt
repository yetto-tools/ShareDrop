package com.erashon.sharedrop.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.erashon.sharedrop.core.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

data class Device(
    val id: String,
    val name: String,
    val platform: String,
    val address: String,
    val port: Int,
    val lastSeen: Long,
    /** "usb-adb" si se llegó por el túnel USB; null si fue por la red. */
    val via: String? = null,
    /** Datos que el equipo anuncia sobre sí mismo (modelo, batería, medio…); null si no manda ninguno. */
    val info: org.json.JSONObject? = null,
)

/** Anuncia este equipo y escucha a los demás por broadcast UDP (Wi-Fi, hotspot, USB tethering, etc.). */
class LanDiscovery(private val ctx: Context, private val store: Store, private val linkName: () -> String? = { null }) {
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val threads = mutableListOf<Thread>()

    fun start() {
        if (running) return
        running = true
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("sharedrop").apply { setReferenceCounted(false); acquire() }
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(java.net.InetSocketAddress(PORT))
        }
        socket = s
        threads += Thread({ listen(s) }, "discovery-rx").also { it.isDaemon = true; it.start() }
        threads += Thread({ announce(s) }, "discovery-tx").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        runCatching { lock?.release() }
        threads.clear()
        _devices.value = emptyList()
    }

    private fun announce(s: DatagramSocket) {
        while (running) {
            // Se recalcula en cada ronda: batería y almacenamiento cambian.
            val msg = JSONObject(
                mapOf(
                    "app" to "sharedrop", "v" to 1, "id" to store.deviceId,
                    "name" to store.deviceName, "platform" to "android", "port" to com.erashon.sharedrop.Engine.TCP_PORT,
                    "info" to DeviceInfo.collect(ctx, linkName()),
                ),
            ).toString().toByteArray()
            for (addr in broadcastAddresses()) {
                runCatching { s.send(DatagramPacket(msg, msg.size, addr, PORT)) }
            }
            prune()
            Thread.sleep(2000)
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                ni.interfaceAddresses.mapNotNullTo(out) { it.broadcast }
            }
        }
        return out.distinct()
    }

    private fun listen(s: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val j = JSONObject(String(p.data, 0, p.length))
                if (j.optString("app") != "sharedrop") continue
                val id = j.getString("id")
                if (id == store.deviceId) continue
                val d = Device(
                    id, j.getString("name"), j.optString("platform", "?"),
                    p.address.hostAddress ?: continue, j.getInt("port"), System.currentTimeMillis(),
                    info = j.optJSONObject("info"),
                )
                // Un equipo alcanzado por el túnel USB (reciente) no lo pisa su anuncio por red.
                val old = _devices.value.firstOrNull { it.id == id }
                if (old?.via != null && System.currentTimeMillis() - old.lastSeen < 6000) continue
                _devices.value = (_devices.value.filter { it.id != id } + d).sortedBy { it.name }
            } catch (e: Exception) {
                if (!running) return
            }
        }
    }

    /** Añade un equipo encontrado por otro camino (el túnel USB por adb). */
    fun inject(d: Device) {
        _devices.value = (_devices.value.filter { it.id != d.id } + d).sortedBy { it.name }
    }

    private fun prune() {
        val limit = System.currentTimeMillis() - 8000
        val cur = _devices.value
        val keep = cur.filter { it.lastSeen >= limit }
        if (keep.size != cur.size) _devices.value = keep
    }

    companion object {
        const val PORT = 47801
    }
}
