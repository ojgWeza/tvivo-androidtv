package com.dev.Tvivo.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeTitlePolicyTest {
    @Test
    fun providerTitlesStayVisibleAndOnlyGenericOrBlankTitlesUseTheOrdinalFallback() {
        assertEquals("Blue Monday", EpisodeTitlePolicy.displayTitle("Blue Monday", 1, "The Show"))
        assertEquals("S02E04", EpisodeTitlePolicy.displayTitle("S02E04", 4, "The Show"))
        assertEquals("الحلقة الأخيرة", EpisodeTitlePolicy.displayTitle("الحلقة الأخيرة", 4, "المسلسل"))
        assertEquals("Episode 4", EpisodeTitlePolicy.displayTitle("Episode 1", 4, "The Show"))
        assertEquals("Episode 4", EpisodeTitlePolicy.displayTitle("  ", 4, "The Show"))
    }

    @Test
    fun ordinalIsSeparateMetadataOnlyForSeasonEpisodeTitles() {
        assertTrue(EpisodeTitlePolicy.showOrdinal("S02E04"))
        assertFalse(EpisodeTitlePolicy.showOrdinal("Song for the Road"))
        assertFalse(EpisodeTitlePolicy.showOrdinal("Episode 2"))
        assertTrue(EpisodeTitlePolicy.isGenericTitle("الحلقة 2"))
    }
}
