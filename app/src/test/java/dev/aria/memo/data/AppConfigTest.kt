package dev.aria.memo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AppConfigTest {

    @Test
    fun `isConfigured is false when any required field is blank`() {
        assertFalse(AppConfig(pat = "", owner = "o", repo = "r").isConfigured)
        assertFalse(AppConfig(pat = "p", owner = "", repo = "r").isConfigured)
        assertFalse(AppConfig(pat = "p", owner = "o", repo = "").isConfigured)
        assertFalse(AppConfig(pat = "   ", owner = "o", repo = "r").isConfigured)
    }

    @Test
    fun `isConfigured is true when pat owner repo all set`() {
        assertTrue(AppConfig(pat = "gho_x", owner = "o", repo = "r").isConfigured)
    }

    @Test
    fun `filePathFor pads month and day with zero`() {
        val config = AppConfig(pat = "p", owner = "o", repo = "r")
        assertEquals("2026-01-05.md", config.filePathFor(LocalDate.of(2026, 1, 5)))
        assertEquals("2026-11-09.md", config.filePathFor(LocalDate.of(2026, 11, 9)))
        assertEquals("2026-12-31.md", config.filePathFor(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `filePathFor respects custom template`() {
        val config = AppConfig(
            pat = "p", owner = "o", repo = "r",
            pathTemplate = "notes/{yyyy}/{MM}/{dd}.md",
        )
        assertEquals("notes/2026/04/21.md", config.filePathFor(LocalDate.of(2026, 4, 21)))
    }
}
