package com.grapie.pickitup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WbClientTest {
    private val msk = ZoneId.of("Europe/Moscow")
    private fun midnight(d: String) = LocalDate.parse(d).atStartOfDay(msk).toInstant().toEpochMilli()

    // Shape of the real answer (values made up).
    private val body = """[
        {"date":"2026-11-19T12:00:00+00:00","code":"22222","extCode":"654321","qr":"111111111_22222_654321_1","extQr":"111111111_654321_1"},
        {"date":"2026-11-18T12:00:00+00:00","code":"11111","extCode":"123456","qr":"111111111_11111_123456_1","extQr":"111111111_123456_1"}
    ]"""

    @Test fun parsesDaysInOrder() {
        val days = WbClient.parse(body, 42)!!
        assertEquals(2, days.size)
        assertEquals("111111111_123456_1", days[0].code)  // the QR the site shows is extQr
        assertEquals("123 456", days[0].visible)          // extCode, as "NNN NNN"
        assertEquals(CodeFormat.QR, days[0].format)
        assertEquals(midnight("2026-11-18"), days[0].validFrom)
        assertEquals(midnight("2026-11-19"), days[1].validFrom)
        assertEquals(42L, days[1].fetchedAt)
    }

    @Test fun rejectsJunk() {
        assertNull(WbClient.parse("[]", 0))
        assertNull(WbClient.parse("{\"error\":1}", 0))
        assertNull(WbClient.parse("<html>", 0))
    }

    @Test fun spacesSixDigits() {
        assertEquals("987 654", WbClient.spaced("987654"))
        assertEquals("12345", WbClient.spaced("12345"))
    }

    @Test fun watchDaysLines() {
        val days = WbClient.parse(body, 0)!!
        assertEquals("${midnight("2026-11-19") / 1000}|111111111_654321_1|654 321", WatchNames.days(days.drop(1)))
    }
}
