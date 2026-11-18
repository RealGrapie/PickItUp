package com.grapie.pickitup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    @Test fun numericParts() {
        assertTrue(Updater.isNewer("v1.10", "1.9"))
        assertTrue(Updater.isNewer("1.5.1", "1.5"))
        assertFalse(Updater.isNewer("1.5", "1.5.0"))
        assertFalse(Updater.isNewer("1.4.3", "1.5"))
    }

    @Test fun preReleaseIsOlderThanItsRelease() {
        assertTrue(Updater.isNewer("1.6", "1.6-beta.3"))
        assertFalse(Updater.isNewer("1.6-beta.1", "1.6"))
        assertTrue(Updater.isNewer("1.6-beta.1", "1.5.1"))
        assertFalse(Updater.isNewer("1.6-beta.1", "1.6.1"))
    }

    @Test fun preReleasesInOrder() {
        val sorted = listOf("1.6", "1.6-rc.1", "1.6-beta.10", "1.6-beta.2", "1.6-beta", "1.6-alpha.1", "1.5.1")
            .sortedWith { a, b -> Updater.compareVersions(a, b) }
        assertEquals(listOf("1.5.1", "1.6-alpha.1", "1.6-beta", "1.6-beta.2", "1.6-beta.10", "1.6-rc.1", "1.6"), sorted)
        assertEquals(0, Updater.compareVersions("v1.6-beta.1", "1.6-BETA.1"))
    }

    @Test fun marksPreReleases() {
        assertTrue(Updater.isPrerelease("1.6-beta.1"))
        assertFalse(Updater.isPrerelease("1.6"))
    }
}
