package com.dev.Tvivo.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashDiagnosticsTest {

    @Test
    fun `report includes exception types and stack frames`() {
        val crash = IllegalStateException("secret").apply {
            stackTrace = arrayOf(StackTraceElement("example.Screen", "open", "Screen.kt", 42))
        }

        val report = CrashDiagnostics.format(crash)

        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("example.Screen.open(Screen.kt:42)"))
    }

    @Test
    fun `report never includes exception messages`() {
        val credentialUrl = "http://user:password@example.invalid/movie/123"
        val crash = IllegalStateException(credentialUrl, IllegalArgumentException("account title"))

        val report = CrashDiagnostics.format(crash)

        assertFalse(report.contains(credentialUrl))
        assertFalse(report.contains("account title"))
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
    }
}