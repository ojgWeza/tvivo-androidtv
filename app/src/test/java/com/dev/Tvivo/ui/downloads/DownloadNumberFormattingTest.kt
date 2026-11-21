package com.dev.Tvivo.ui.downloads

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DownloadNumberFormattingTest {
    @Test fun sizesUseLocaleDigitsAndKeepNumberAndUnitIsolated() {
        val formatted = formatBytes(155_000_000L, Locale("ar"))
        assertTrue(formatted.contains("١٥٥"))
        assertTrue(formatted.contains("MB"))
        assertTrue(formatted.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT_ISOLATE ||
            Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING ||
            Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING })
    }

    @Test fun progressAndUsageNumbersUseLocaleFormatting() {
        assertEquals("١٠٠", bidiNumber(100.0, Locale("ar"), 0))
        assertTrue(bidiNumber(1.55, Locale("ar"), 2).contains("١٫٥٥"))
    }
}
