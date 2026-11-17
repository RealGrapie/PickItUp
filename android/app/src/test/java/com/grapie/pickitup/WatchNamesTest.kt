package com.grapie.pickitup

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchNamesTest {
    @Test fun transliterates() {
        assertEquals("Mama", WatchNames.ascii("Мама"))
        assertEquals("Papa", WatchNames.ascii("Папа"))
        assertEquals("Zhenya i Mama", WatchNames.ascii("Женя и Мама"))
        assertEquals("Mom", WatchNames.ascii("Mom 😀"))
    }
}
