package com.dev.Tvivo.ui.common

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ParagraphDirectionTest {
    @Test
    fun `arabic paragraph ignores leading punctuation and aligns right`() {
        val text = "… للمسلسل موسم جديد"

        assertEquals(TextDirection.Rtl, paragraphDirection(text))
        assertEquals(TextAlign.Right, paragraphAlignment(text))
    }

    @Test
    fun `latin paragraph stays left aligned`() {
        val text = "A new season is available"

        assertEquals(TextDirection.Ltr, paragraphDirection(text))
        assertEquals(TextAlign.Left, paragraphAlignment(text))
    }
}
