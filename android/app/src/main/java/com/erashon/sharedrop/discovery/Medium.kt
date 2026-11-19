package com.erashon.sharedrop.discovery

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Medio por el que se ve a un dispositivo. `key`: "usb", "wifi" o "net" (decide el color de la etiqueta). */
data class Medium(val key: String, val label: String)

/** Medio de conexión con [d]: el túnel USB, lo que el propio equipo anuncia, o la interfaz donde se le ve. */
fun mediumOf(d: Device): Medium? {
    if (d.via == "usb-adb") return Medium("usb", "USB · cable")
    when (d.info?.optString("link")) {
        "usb" -> return Medium("usb", "USB")
        "wifi" -> return Medium("wifi", "Wi-Fi")
        "hotspot" -> return Medium("wifi", "Zona Wi-Fi")
    }
    return runCatching {
        val target = InetAddress.getByName(d.address).address
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address
                if (a is Inet4Address && sameSubnet(a.address, target, ia.networkPrefixLength.toInt())) return@runCatching labelFor(ni.name)
            }
        }
        null
    }.getOrNull()
}

private fun labelFor(name: String): Medium {
    val n = name.lowercase()
    return when {
        n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> Medium("usb", "USB")
        n.startsWith("swlan") || n == "wlan1" -> Medium("wifi", "Zona Wi-Fi")
        n.startsWith("p2p") -> Medium("wifi", "Wi-Fi Direct")
        n.startsWith("bt-pan") || n.startsWith("bnep") -> Medium("net", "Bluetooth")
        n.startsWith("wlan") -> Medium("wifi", "Wi-Fi")
        else -> Medium("net", "Red")
    }
}

/** ¿Están [a] y [b] (IPv4) en la misma subred de [prefix] bits? */
internal fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
    if (a.size != 4 || b.size != 4 || prefix !in 0..32) return false
    var bits = prefix
    for (i in 0 until 4) {
        val take = minOf(8, bits)
        if (take > 0) {
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
        }
        bits -= take
    }
    return true
}
