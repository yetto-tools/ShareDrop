package com.erashon.sharedrop.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Red Wi-Fi propia del teléfono (LocalOnlyHotspot): sin internet ni router, para conectar una laptop
 * cuando no comparten ninguna red (p. ej. una red corporativa que aísla a los equipos).
 */
class HotspotController(private val ctx: Context) {
    data class State(
        val active: Boolean = false,
        val starting: Boolean = false,
        val ssid: String? = null,
        val password: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** Requiere el permiso (NEARBY_WIFI_DEVICES en Android 13+, ubicación antes): lo pide la pantalla. */
    @SuppressLint("MissingPermission")
    fun start() {
        val cur = _state.value
        if (cur.active || cur.starting) return
        _state.value = State(starting = true)
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val (ssid, pass) = credentials(r)
                    _state.value = State(active = true, ssid = ssid, password = pass)
                }

                override fun onStopped() {
                    reservation = null
                    _state.value = State()
                }

                override fun onFailed(reason: Int) {
                    _state.value = State(error = reasonText(reason))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            _state.value = State(error = e.message ?: "No se pudo crear la red")
        }
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
        _state.value = State()
    }

    @Suppress("DEPRECATION")
    private fun credentials(r: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> =
        if (Build.VERSION.SDK_INT >= 30) r.softApConfiguration.let { it.ssid to it.passphrase }
        else r.wifiConfiguration.let { it?.SSID to it?.preSharedKey }

    private fun reasonText(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No hay un canal Wi-Fi disponible"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "El sistema no permite compartir red en este momento"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Apaga el hotspot del sistema e inténtalo de nuevo"
        else -> "No se pudo crear la red (código $reason)"
    }
}
