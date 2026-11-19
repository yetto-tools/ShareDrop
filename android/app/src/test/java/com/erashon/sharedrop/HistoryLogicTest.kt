package com.erashon.sharedrop

import com.erashon.sharedrop.core.HistoryEntry
import com.erashon.sharedrop.core.HistoryFilter
import com.erashon.sharedrop.core.HistoryLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HistoryLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun entry(uid: String, incoming: Boolean, at: Long, ok: Boolean = true) =
        HistoryEntry(uid, "Redmi", "$uid.txt", 10, incoming, ok, null, null, at)

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance(utc).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    @Test
    fun `conserva las ultimas 20 de cada direccion`() {
        var items = emptyList<HistoryEntry>()
        for (i in 1..30) {
            items = HistoryLogic.add(items, entry("r$i", true, 1000L + i))
            items = HistoryLogic.add(items, entry("s$i", false, 5000L + i))
        }
        assertEquals(20, items.count { it.incoming })
        assertEquals(20, items.count { !it.incoming })
        assertTrue(items.any { it.uid == "r30" })
        assertFalse("la mas antigua se descarta", items.any { it.uid == "r1" })
        assertFalse(items.any { it.uid == "s10" })
    }

    @Test
    fun `una entrada con el mismo uid se actualiza y no se duplica`() {
        var items = HistoryLogic.add(emptyList(), entry("a", true, 100, ok = true))
        items = HistoryLogic.add(items, entry("a", true, 999, ok = false))
        assertEquals(1, items.size)
        assertFalse(items[0].ok)
        assertEquals("conserva el momento original", 100L, items[0].at)
    }

    @Test
    fun `cada pestana muestra como maximo 20 y lo mas reciente primero`() {
        val items = (1..20).map { entry("r$it", true, 1000L + it) } + (1..20).map { entry("s$it", false, 5000L + it) }
        assertEquals(20, HistoryLogic.visible(items, HistoryFilter.Received).size)
        assertEquals(20, HistoryLogic.visible(items, HistoryFilter.Sent).size)
        assertEquals("Todo tambien se queda en 20", 20, HistoryLogic.visible(items, HistoryFilter.All).size)
        assertEquals("r20", HistoryLogic.visible(items, HistoryFilter.Received).first().uid)
        assertEquals("s20", HistoryLogic.visible(items, HistoryFilter.All).first().uid)
        assertTrue(HistoryLogic.visible(items, HistoryFilter.Sent).none { it.incoming })
        assertTrue(HistoryLogic.visible(emptyList(), HistoryFilter.All).isEmpty())
    }

    @Test
    fun `whenLabel distingue hoy ayer y fechas anteriores`() {
        val now = ms(2026, 11, 18, 18, 0)
        assertEquals("Hoy · 16:31", HistoryLogic.whenLabel(ms(2026, 11, 18, 16, 31), now, utc))
        assertEquals("Ayer · 09:05", HistoryLogic.whenLabel(ms(2026, 11, 17, 9, 5), now, utc))
        assertEquals("cruza la medianoche", "Ayer · 23:59", HistoryLogic.whenLabel(ms(2026, 11, 17, 23, 59), ms(2026, 11, 18, 0, 5), utc))
        assertEquals("01/11/2026 · 08:00", HistoryLogic.whenLabel(ms(2026, 11, 1, 8, 0), now, utc))
        assertEquals("31/12/2025 · 23:00", HistoryLogic.whenLabel(ms(2025, 12, 31, 23, 0), now, utc))
    }
}
