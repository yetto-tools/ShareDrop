package com.erashon.sharedrop.discovery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/** Qué medios hay hoy entre este teléfono y otro equipo. */
data class LinkState(
    val wifiOn: Boolean = true,
    /** Conectado a una red Wi-Fi (puede tener o no a la otra laptop). */
    val wifiConnected: Boolean = false,
    /** Cable USB conectado a un equipo o cargador. */
    val usbPlugged: Boolean = false,
    /** Hay una interfaz de USB tethering con IP. */
    val usbTether: Boolean = false,
    /** Este teléfono está compartiendo su propia red (hotspot del sistema o de la app). */
    val hotspot: Boolean = false,
    val bluetoothTether: Boolean = false,
    val wifiDirect: Boolean = false,
) {
    /** ¿Hay algún enlace por el que otro equipo podría llegar? */
    val anyLink get() = wifiConnected || usbTether || hotspot || bluetoothTether || wifiDirect
}

/** Observa cada 2 s las interfaces de red; la interfaz gráfica y las notificaciones reaccionan a [state]. */
class LinkMonitor(private val ctx: Context) {
    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> = _state

    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({
            while (running) {
                _state.value = read()
                Thread.sleep(2000)
            }
        }, "link-monitor").also { it.isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }

    private fun read(): LinkState {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        var wifiConnected = false
        var usb = false
        var hotspot = false
        var bt = false
        var p2p = false
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                if (ni.interfaceAddresses.none { it.address is Inet4Address }) continue
                val n = ni.name.lowercase()
                when {
                    n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> usb = true
                    // "ap0" no cuenta: en algunos teléfonos (MediaTek) existe siempre aunque no haya hotspot.
                    n.startsWith("swlan") || n == "wlan1" -> hotspot = true
                    n.startsWith("bt-pan") || n.startsWith("bnep") -> bt = true
                    n.startsWith("p2p") -> p2p = true
                    n.startsWith("wlan") -> wifiConnected = true
                }
            }
        }
        val plugged = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == BatteryManager.BATTERY_PLUGGED_USB
        return LinkState(
            wifiOn = wm.isWifiEnabled,
            wifiConnected = wifiConnected,
            usbPlugged = plugged,
            usbTether = usb,
            hotspot = hotspot,
            bluetoothTether = bt,
            wifiDirect = p2p,
        )
    }
}
