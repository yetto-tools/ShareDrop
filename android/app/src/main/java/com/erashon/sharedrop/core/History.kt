package com.erashon.sharedrop.core

import android.content.Context
import com.erashon.sharedrop.Transfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Historial de transferencias terminadas, guardado en el teléfono: sobrevive al cierre de la app.
 * Las reglas (últimas 20 de cada tipo) están en [HistoryLogic].
 */
class History(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("sharedrop-history", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    /** Anota una transferencia terminada (con éxito o con error). Las que siguen en curso se ignoran. */
    @Synchronized
    fun record(t: Transfer, session: Long) {
        if (t.state == Transfer.State.Active) return
        val entry = HistoryEntry(
            uid = "$session-${t.id}",
            peer = t.peer,
            file = t.file,
            size = t.total,
            incoming = t.incoming,
            ok = t.state == Transfer.State.Finished,
            error = t.error,
            uri = t.uri,
            at = System.currentTimeMillis(),
        )
        _entries.value = HistoryLogic.add(_entries.value, entry)
        save()
    }

    private fun load(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            HistoryEntry(
                uid = o.getString("uid"),
                peer = o.getString("peer"),
                file = o.getString("file"),
                size = o.getLong("size"),
                incoming = o.getBoolean("incoming"),
                ok = o.getBoolean("ok"),
                error = o.optString("error").takeIf { it.isNotEmpty() },
                uri = o.optString("uri").takeIf { it.isNotEmpty() },
                at = o.getLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray()
        for (e in _entries.value) {
            arr.put(
                JSONObject()
                    .put("uid", e.uid).put("peer", e.peer).put("file", e.file).put("size", e.size)
                    .put("incoming", e.incoming).put("ok", e.ok).put("error", e.error ?: "")
                    .put("uri", e.uri ?: "").put("at", e.at),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "items"
    }
}
