package com.grapie.pickitup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalTest {
    @Test fun newFormat() {
        val e = Journal.parse(Journal.line(1_000L, "Ozon · Мама", "✅ Штрихкод обновлён: …1234"))!!
        assertEquals(1_000L, e.time)
        assertEquals("Ozon · Мама", e.account)
        assertEquals("✅", e.icon)
        assertEquals("Штрихкод обновлён: …1234", e.text)
        assertNull(Journal.parse(Journal.line(5L, null, "plain"))!!.account)
    }

    @Test fun rowsMergeAndCollapse() {
        val e = listOf(
            Journal.line(0, "A", "🔄 Запрашиваем штрихкод у Ozon…"),
            Journal.line(1000, "A", "🔐 Токены входа обновлены: 759f/496c → 1a2b/77ce"),
            Journal.line(4000, "A", "✅ Штрихкод обновлён: …1234"),
            Journal.line(5000, null, "🔐 Токены входа: access:aa11 refresh:bb22 → access:aa11 refresh:bb22"),
            Journal.line(6000, null, "⌚ watchapp запросил штрихкод"),
            Journal.line(9000, null, "⌚ watchapp запросил штрихкод"),
        ).mapNotNull(Journal::parse)
        val rows = Journal.rows(e)
        assertEquals(2, rows.size)
        assertEquals(2, rows[0].count)
        assertEquals("✅", rows[1].entry.icon)
        assertEquals(4, rows[1].seconds)
        assertEquals("759f/496c → 1a2b/77ce", rows[1].tokens)
    }

    @Test fun oldFormat() {
        val e = Journal.parse("18.11 06:28:56  Ozon · Мама: ⌚ watchapp просит обновить")!!
        assertEquals("Ozon · Мама", e.account)
        assertEquals("⌚", e.icon)
        assertEquals("watchapp просит обновить", e.text)
        val p = Journal.parse("16.11 12:16:41  Requesting the barcode from Ozon…")!!
        assertNull(p.account)
        assertEquals("", p.icon)
    }
}
