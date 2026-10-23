package com.grapie.pickitup

import java.util.Calendar

/** How the barcode is refreshed: every N hours, or once a day at a chosen time. */
enum class RefreshMode { INTERVAL, DAILY }

object RefreshSchedule {
    val INTERVAL_OPTIONS = listOf(1, 3, 6, 12, 24)
    const val DEFAULT_INTERVAL_HOURS = 6
    const val DEFAULT_DAILY_MINUTES = 8 * 60  // 08:00

    fun formatTime(minutesOfDay: Int) = "%02d:%02d".format(minutesOfDay / 60, minutesOfDay % 60)

    /** The latest moment at [minutesOfDay] that is not after [now]. */
    fun lastDaily(now: Long, minutesOfDay: Int): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minutesOfDay / 60)
            set(Calendar.MINUTE, minutesOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis > now) c.add(Calendar.DAY_OF_YEAR, -1)
        return c.timeInMillis
    }

    /** The next moment at [minutesOfDay] strictly after [now]. */
    fun nextDaily(now: Long, minutesOfDay: Int): Long {
        val c = Calendar.getInstance().apply { timeInMillis = lastDaily(now, minutesOfDay) }
        c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** A saved code is stale when it is older than the interval / older than the last daily time. */
    fun isStale(fetchedAt: Long, now: Long = System.currentTimeMillis()): Boolean = when (BarcodeStore.refreshMode.value) {
        RefreshMode.INTERVAL -> now - fetchedAt > BarcodeStore.refreshHours.value * 3_600_000L
        RefreshMode.DAILY -> fetchedAt < lastDaily(now, BarcodeStore.dailyMinutes.value)
    }
}
