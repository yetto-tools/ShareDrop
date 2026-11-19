package com.erashon.sharedrop.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.UUID

/** Identidad local y secretos de los equipos ya emparejados. */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("sharedrop", Context.MODE_PRIVATE)

    /** Nombre que el usuario le puso al teléfono en Ajustes (p. ej. "Redmi Note 14 5G"); si no hay, el modelo. */
    private fun friendlyName(): String =
        runCatching { Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    val deviceId: String = prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("id", it).apply()
    }

    var deviceName: String
        get() = prefs.getString("name", null) ?: friendlyName()
        set(v) = prefs.edit().putString("name", v).apply()

    /** Carpeta elegida por el usuario (URI de árbol del Storage Access Framework); null = Download/ShareDrop. */
    var receiveTree: String?
        get() = prefs.getString("receiveTree", null)
        set(v) = prefs.edit().apply { if (v == null) remove("receiveTree") else putString("receiveTree", v) }.apply()

    /** Apariencia elegida: "system" (la del teléfono), "light" u "dark". */
    private val _theme = MutableStateFlow(prefs.getString("theme", "system") ?: "system")
    val theme: StateFlow<String> = _theme
    fun setTheme(v: String) {
        prefs.edit().putString("theme", v).apply()
        _theme.value = v
    }

    private fun peers(): JSONObject = JSONObject(prefs.getString("peers", "{}")!!)

    fun secretFor(id: String): ByteArray? = peers().optString(id, "").takeIf { it.isNotEmpty() }?.unhex()

    fun isPaired(id: String) = secretFor(id) != null

    fun savePeer(id: String, secret: ByteArray) {
        prefs.edit().putString("peers", peers().put(id, secret.hex()).toString()).apply()
    }
}
