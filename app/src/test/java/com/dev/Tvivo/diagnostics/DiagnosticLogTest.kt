package com.dev.Tvivo.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DiagnosticLogTest {

    @Test
    fun `schema entry retains the stable diagnostic fields`() {
        val entry = DiagnosticLog.Entry(1L, "Home", "tile activated", "tile=LIVE", DiagnosticLog.Level.INFO)

        assertEquals(1L, entry.timestamp)
        assertEquals("Home", entry.screen)
        assertEquals("tile activated", entry.event)
        assertEquals("tile=LIVE", entry.payload)
        assertEquals(DiagnosticLog.Level.INFO, entry.severity)
    }

    @Test
    fun `redaction removes credentials tokens and authorization`() {
        val value = "http://alice:secret@example.invalid/?token=abc password=hunter2 Bearer xyz"
        val sanitized = DiagnosticLog.redact(value)

        assertFalse(sanitized.contains("alice"))
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("xyz"))
        assertFalse(sanitized.contains("example.invalid"))
    }

    @Test
    fun `serialized entries restore after a process restart`() {
        val file = File.createTempFile("tvivo-diagnostics", ".log")
        val expected = DiagnosticLog.Entry(7L, "Home", "route state mutated", "contentType=LIVE", DiagnosticLog.Level.INFO)
        try {
            file.writeText(DiagnosticLog.encode(listOf(expected)))

            assertEquals(listOf(expected), DiagnosticLog.read(file))
        } finally {
            assertTrue(file.delete() || !file.exists())
        }
    }
}
