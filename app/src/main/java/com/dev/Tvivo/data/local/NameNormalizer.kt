package com.dev.Tvivo.data.local

import java.text.Normalizer
import java.util.Locale

/**
 * Three names per row, produced together in the same transaction as the write.
 *
 * `display` exists because rendering the raw name breaks truncation: a title like
 * `"HD  للعدالة وجه آخر"` opens with a strong LTR run, so Compose resolves the whole
 * paragraph LTR, the Arabic lays out RTL inside a left-aligned box, and the ellipsis
 * lands on the wrong visual edge. Stripping the leading quality token fixes it for a
 * large fraction of the catalog.
 */
object NameNormalizer {

    data class Names(val raw: String, val display: String, val normalized: String)

    private val QUALITY_TOKENS = setOf(
        "hd", "fhd", "sd", "uhd", "4k", "1080p", "720p", "480p", "2160p", "hevc", "h265"
    )

    private val ARABIC_DIACRITICS = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private val WHITESPACE = Regex("\\s+")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    fun of(raw: String): Names {
        val display = toDisplay(raw)
        return Names(raw = raw, display = display, normalized = toNormalized(display))
    }

    private fun toDisplay(raw: String): String {
        var s = raw.replace(ARABIC_DIACRITICS, "")
        s = WHITESPACE.replace(s, " ").trim()

        // Quality tokens are stripped from the edges only. Removing them mid-title would
        // mangle real names that happen to contain "4K" or "HD".
        var changed = true
        while (changed) {
            changed = false
            val head = s.substringBefore(' ', s)
            if (isQualityToken(head) && head.length < s.length) {
                s = s.removePrefix(head).trim()
                changed = true
            }
            val tail = s.substringAfterLast(' ', s)
            if (isQualityToken(tail) && tail.length < s.length) {
                s = s.removeSuffix(tail).trim()
                changed = true
            }
        }
        return s.ifEmpty { raw.trim() }
    }

    private fun isQualityToken(token: String): Boolean {
        val cleaned = token.trim().trim('[', ']', '(', ')', '-', '|', ':').lowercase(Locale.ROOT)
        return cleaned.isNotEmpty() && cleaned in QUALITY_TOKENS
    }

    private fun toNormalized(display: String): String {
        val lowered = display.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        return COMBINING_MARKS.replace(decomposed, "")
            .let { Normalizer.normalize(it, Normalizer.Form.NFC) }
            .let { WHITESPACE.replace(it, " ") }
            .trim()
    }
}
