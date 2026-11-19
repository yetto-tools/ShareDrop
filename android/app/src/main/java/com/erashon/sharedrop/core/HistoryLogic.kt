package com.erashon.sharedrop.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/** Transferencia terminada, guardada en el historial. */
data class HistoryEntry(
    /** Sesión + id de la transferencia: los ids se reinician en cada arranque. */
    val uid: String,
    val peer: String,
    val file: String,
    /** Bytes (-1 si se desconoce). */
    val size: Long,
    val incoming: Boolean,
    val ok: Boolean,
    val error: String?,
    /** Dónde quedó guardado un archivo recibido (para abrirlo). */
    val uri: String?,
    /** Cuándo terminó, en milisegundos desde 1970. */
    val at: Long,
)

/** Pestañas del historial. */
enum class HistoryFilter { All, Received, Sent }

/** Reglas del historial, sin dependencias de Android: se prueban con JUnit en el ordenador. */
object HistoryLogic {
    /** Cuántas transferencias se conservan de cada tipo (recibidas / enviadas) y cuántas muestra cada pestaña. */
    const val KEEP = 20

    /**
     * Añade una entrada (o actualiza la que tenga el mismo `uid`) y conserva solo las últimas [keep] de
     * cada dirección. Devuelve la lista ordenada de más antigua a más reciente.
     */
    fun add(items: List<HistoryEntry>, entry: HistoryEntry, keep: Int = KEEP): List<HistoryEntry> {
        val i = items.indexOfFirst { it.uid == entry.uid }
        val merged = if (i >= 0) items.toMutableList().also { it[i] = entry.copy(at = items[i].at) } else items + entry
        return listOf(true, false)
            .flatMap { dir -> merged.filter { it.incoming == dir }.sortedBy { it.at }.takeLast(keep) }
            .sortedBy { it.at }
    }

    /** Lo que muestra una pestaña: las [limit] más recientes, la más reciente primero. */
    fun visible(items: List<HistoryEntry>, filter: HistoryFilter, limit: Int = KEEP): List<HistoryEntry> =
        items.sortedByDescending { it.at }
            .filter {
                when (filter) {
                    HistoryFilter.All -> true
                    HistoryFilter.Received -> it.incoming
                    HistoryFilter.Sent -> !it.incoming
                }
            }
            .take(limit)

    /** Cuándo terminó: "Hoy · 16:31", "Ayer · 09:05" o "18/11/2026 · 16:31". */
    fun whenLabel(at: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        fun cal(ms: Long) = Calendar.getInstance(zone).apply { timeInMillis = ms }
        fun dayStart(ms: Long) = cal(ms).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val c = cal(at)
        val time = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        // Se redondea para no depender del cambio de hora (un día puede durar 23 o 25 horas).
        val days = ((dayStart(now) - dayStart(at)) / 86_400_000.0).roundToInt()
        return when (days) {
            0 -> "Hoy · $time"
            1 -> "Ayer · $time"
            else -> "%02d/%02d/%d · %s".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR), time)
        }
    }
}
