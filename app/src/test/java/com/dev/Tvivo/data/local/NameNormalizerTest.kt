package com.dev.Tvivo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `name_display` exists because rendering the raw name breaks truncation on a catalog
 * that is largely Arabic and mixed Arabic/English. `name_normalized` is what search
 * matches and what every list sorts on.
 */
class NameNormalizerTest {

    @Test
    fun `raw name is preserved untouched`() {
        val names = NameNormalizer.of("HD  Some Movie (2026)")
        assertEquals("HD  Some Movie (2026)", names.raw)
    }

    @Test
    fun `leading quality token is stripped from display`() {
        assertEquals("Some Movie (2026)", NameNormalizer.of("HD Some Movie (2026)").display)
        assertEquals("Some Movie", NameNormalizer.of("FHD Some Movie").display)
        assertEquals("Some Movie", NameNormalizer.of("4K Some Movie").display)
    }

    @Test
    fun `trailing quality token is stripped from display`() {
        assertEquals("Some Movie", NameNormalizer.of("Some Movie HD").display)
    }

    @Test
    fun `collapsed whitespace`() {
        assertEquals("Some Movie", NameNormalizer.of("Some    Movie").display)
        assertEquals("Some Movie", NameNormalizer.of("  Some Movie  ").display)
    }

    /**
     * The exact title from the API reference: a strong LTR run opening an Arabic title is
     * what makes Compose resolve the paragraph LTR and put the ellipsis on the wrong edge.
     */
    @Test
    fun `arabic title loses its leading latin quality token`() {
        val names = NameNormalizer.of("HD  للعدالة وجه آخر")
        assertFalse(names.display.startsWith("HD"))
        assertTrue(names.display.startsWith("للعدالة"))
    }

    @Test
    fun `display keeps case and the normalized form lowercases`() {
        val names = NameNormalizer.of("Some Movie")
        assertEquals("Some Movie", names.display)
        assertEquals("some movie", names.normalized)
    }

    @Test
    fun `latin diacritics are stripped for matching but kept for display`() {
        val names = NameNormalizer.of("Amélie")
        assertEquals("Amélie", names.display)
        assertEquals("amelie", names.normalized)
    }

    /**
     * A quality token mid-title is a real word, not metadata — stripping it everywhere
     * would mangle titles that legitimately contain "HD" or "4K".
     */
    @Test
    fun `quality token inside the title is left alone`() {
        assertEquals("The HD Story", NameNormalizer.of("The HD Story").display)
    }

    @Test
    fun `a title that is only a quality token does not become empty`() {
        val names = NameNormalizer.of("HD")
        assertTrue(names.display.isNotEmpty())
    }

    @Test
    fun `normalized form is stable for sorting comparisons`() {
        // Alphabetical ordering is on this column, so equal titles must normalize equally
        // regardless of the decoration the panel attached.
        assertEquals(
            NameNormalizer.of("HD Some Movie").normalized,
            NameNormalizer.of("Some Movie FHD").normalized
        )
    }
}
