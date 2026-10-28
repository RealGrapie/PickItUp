package com.grapie.pickitup

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    private val sections = """
        ## English
        **The app checks the watch on start.**

        - On start the app checks the watch.
        - Daily refresh at 06:00 by default.

        ## Русский
        **Часы сразу знают, открыт ли watchapp.**

        - При запуске приложение проверяет часы.
        - Обновление в 06:00 по умолчанию.
    """.trimIndent()

    // The format of v1.2 - v1.4.1: RU and EN points interleaved under one heading.
    private val interleaved = """
        Что нового / What's new:
        - Пункт один.
        - Point one.
        - Пункт два.
        - Point two.
    """.trimIndent()

    @Test fun sectionsRussian() = assertEquals(
        ReleaseNotes("Часы сразу знают, открыт ли watchapp.", listOf("При запуске приложение проверяет часы.", "Обновление в 06:00 по умолчанию.")),
        localizedNotes(sections, russian = true),
    )

    @Test fun sectionsEnglish() = assertEquals(
        ReleaseNotes("The app checks the watch on start.", listOf("On start the app checks the watch.", "Daily refresh at 06:00 by default.")),
        localizedNotes(sections, russian = false),
    )

    @Test fun interleavedHasNoHighlight() {
        assertEquals(ReleaseNotes(null, listOf("Пункт один.", "Пункт два.")), localizedNotes(interleaved, russian = true))
        assertEquals(ReleaseNotes(null, listOf("Point one.", "Point two.")), localizedNotes(interleaved, russian = false))
    }

    @Test fun englishOnlyBodyServesBoth() =
        assertEquals(ReleaseNotes("Fixes.", listOf("A bug.")), localizedNotes("Fixes.\n- A bug.", russian = true))
}
