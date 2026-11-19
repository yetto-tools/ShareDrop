package com.erashon.sharedrop.discovery

import com.erashon.sharedrop.core.Handshake
import com.erashon.sharedrop.core.Store

/**
 * Enlace USB por adb: cuando la PC abre `adb reverse tcp:47810 tcp:47800`, lo que este teléfono envíe a
 * 127.0.0.1:47810 llega a la app de la PC. Como por un túnel no pasa el descubrimiento por broadcast,
 * se sondea ese puerto cada pocos segundos; si hay alguien, se añade a la lista de dispositivos.
 */
class UsbLink(private val store: Store, private val onFound: (Device) -> Unit) {
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({
            while (running) {
                // Sin túnel, la conexión se rechaza al instante: sondear es barato.
                Handshake.probe("127.0.0.1", REVERSE_PORT, store)?.let { r ->
                    onFound(
                        Device(
                            id = r.getString("id"),
                            name = r.getString("name"),
                            platform = r.optString("platform", "windows"),
                            address = "127.0.0.1",
                            port = REVERSE_PORT,
                            lastSeen = System.currentTimeMillis(),
                            via = "usb-adb",
                            info = r.optJSONObject("info"),
                        ),
                    )
                }
                Thread.sleep(3000)
            }
        }, "usb-link").also { it.isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }

    companion object {
        /** Puerto del teléfono que `adb reverse` lleva a la PC (ver PROTOCOL.md). */
        const val REVERSE_PORT = 47810
    }
}
