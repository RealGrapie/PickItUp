package com.grapie.pickitup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RefreshScheduleTest {
    private val hour = 3_600_000L

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
    }

    private fun at(day: Int, h: Int, m: Int = 0): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.OCTOBER, day, h, m)
    }.timeInMillis

    @Test fun lastDailyIsTodayOnceTheTimeHasPassed() {
        assertEquals(at(23, 8), RefreshSchedule.lastDaily(at(23, 9, 30), 8 * 60))
        assertEquals(at(23, 8), RefreshSchedule.lastDaily(at(23, 8), 8 * 60))  // exactly at the time
    }

    @Test fun lastDailyIsYesterdayBeforeTheTime() {
        assertEquals(at(22, 8), RefreshSchedule.lastDaily(at(23, 7, 59), 8 * 60))
    }

    @Test fun nextDailyIsStrictlyLater() {
        assertEquals(at(24, 8), RefreshSchedule.nextDaily(at(23, 8), 8 * 60))
        assertEquals(at(23, 8), RefreshSchedule.nextDaily(at(23, 7), 8 * 60))
    }

    @Test fun formatTime() {
        assertEquals("08:05", RefreshSchedule.formatTime(8 * 60 + 5))
        assertEquals("23:59", RefreshSchedule.formatTime(23 * 60 + 59))
    }

    @Test fun intervalStaleness() {
        BarcodeStore.refreshMode.value = RefreshMode.INTERVAL
        BarcodeStore.refreshHours.value = 6
        val now = at(23, 12)
        assertFalse(RefreshSchedule.isStale(now - 6 * hour, now))
        assertTrue(RefreshSchedule.isStale(now - 6 * hour - 1, now))
    }

    @Test fun dailyStaleness() {
        BarcodeStore.refreshMode.value = RefreshMode.DAILY
        BarcodeStore.dailyMinutes.value = 8 * 60
        assertTrue(RefreshSchedule.isStale(at(23, 7, 59), at(23, 9)))   // fetched before today's 08:00
        assertFalse(RefreshSchedule.isStale(at(23, 8, 1), at(23, 9)))
        assertFalse(RefreshSchedule.isStale(at(22, 9), at(23, 7)))      // today's 08:00 hasn't come yet
    }
}
