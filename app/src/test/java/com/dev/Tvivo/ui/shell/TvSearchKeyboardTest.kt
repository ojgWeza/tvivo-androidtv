package com.dev.Tvivo.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSearchKeyboardTest {
    @Test fun `English and Arabic layouts include letters digits and all controls`() {
        val english = tvSearchKeys(TvSearchLanguage.ENGLISH)
        val arabic = tvSearchKeys(TvSearchLanguage.ARABIC)
        assertEquals(26, english.count { it.action == TvSearchAction.Character && it.label[0].isLetter() })
        assertEquals(10, english.count { it.action == TvSearchAction.Character && it.label[0].isDigit() })
        assertTrue(arabic.any { it.label == "ا" })
        assertEquals(english.map { it.action }.toSet(), arabic.map { it.action }.toSet())
    }

    @Test fun `query editing appends spaces backspaces and clears explicitly`() {
        val keys = tvSearchKeys(TvSearchLanguage.ENGLISH)
        assertEquals("cat", editTvSearchQuery("ca", keys.first { it.label == "t" }))
        assertEquals("cat ", editTvSearchQuery("cat", keys.first { it.action == TvSearchAction.Space }))
        assertEquals("ca", editTvSearchQuery("cat", keys.first { it.action == TvSearchAction.Backspace }))
        assertEquals("", editTvSearchQuery("cat", keys.first { it.action == TvSearchAction.Clear }))
    }

    @Test fun `horizontal wraps within row and down follows keyboard row`() {
        assertEquals(4, tvSearchTarget(0, 41, 5, -1, 0))
        assertEquals(0, tvSearchTarget(4, 41, 5, 1, 0))
        assertEquals(5, tvSearchTarget(0, 41, 5, 0, 1))
        assertEquals(40, tvSearchTarget(40, 41, 5, 0, 1))
    }
}
