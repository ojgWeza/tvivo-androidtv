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

    /** Best first. Drives which single badge a multi-token title shows. */
    private val QUALITY_RANK = listOf(
        "4K", "2160P", "UHD", "FHD", "1080P", "HD", "720P", "HEVC", "H265", "SD", "480P"
    )

    private val ARABIC_DIACRITICS = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private val WHITESPACE = Regex("\\s+")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    fun of(raw: String): Names {
        val display = toDisplay(raw)
        return Names(raw = raw, display = display, normalized = toNormalized(display))
    }

    private fun toDisplay(raw: String): String = strip(raw).display

    /**
     * D-14. Puts a user's filter text through the **same** transform as the stored
     * `normalized` column, so a `LIKE` against it can actually match.
     *
     * This is not a nicety. `normalized` is lowercased, NFD-decomposed, stripped of
     * combining marks and re-composed; comparing raw keystrokes against it fails the
     * moment anyone types a capital, an accented Latin letter, or Arabic with diacritics
     * — all three of which occur in this catalog. Filtering is only as good as the
     * agreement between the two sides of the comparison, so there is exactly one
     * implementation and both sides call it.
     *
     * Note this searches the **quality-stripped** name, because `normalized` is derived
     * from `display`. Typing `HD` therefore does not filter by quality even though the
     * badge on the card says `HD` — the badge comes from [qualityOf], a separate field.
     */
    fun normalizeQuery(input: String): String = toNormalized(input)

    /**
     * The quality tokens [toDisplay] removed, normalised for display — `"HD"`, `"4K"`,
     * `null` when the title carried none.
     *
     * Stripping the token is what makes mixed-direction titles truncate correctly, but on
     * a panel that publishes the same title at several qualities it also made two genuinely
     * different rows render as the same string: `"بطل العالم HD"` and `"بطل العالم SD"`
     * both display as `"بطل العالم"`, so a category looks full of duplicates that are not
     * duplicates. Surfacing the token as a separate badge keeps both properties.
     *
     * Derived from the raw name at map time rather than stored, so this costs no schema
     * change and cannot drift out of sync with `nameDisplay`.
     */
    fun qualityOf(raw: String): String? = strip(raw).quality

    private data class Stripped(val display: String, val quality: String?)

    private fun strip(raw: String): Stripped {
        var s = raw.replace(ARABIC_DIACRITICS, "")
        s = WHITESPACE.replace(s, " ").trim()

        // Quality tokens are stripped from the edges only. Removing them mid-title would
        // mangle real names that happen to contain "4K" or "HD".
        val found = mutableListOf<String>()
        var changed = true
        while (changed) {
            changed = false
            val head = s.substringBefore(' ', s)
            if (isQualityToken(head) && head.length < s.length) {
                found += canonicalQuality(head)
                s = s.removePrefix(head).trim()
                changed = true
            }
            val tail = s.substringAfterLast(' ', s)
            if (isQualityToken(tail) && tail.length < s.length) {
                found += canonicalQuality(tail)
                s = s.removeSuffix(tail).trim()
                changed = true
            }
        }
        // Highest-fidelity token wins: "MOVIE 4K HD" is a 4K stream, and one badge is all
        // a poster caption has room for.
        val quality = found.minByOrNull { QUALITY_RANK.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return Stripped(display = s.ifEmpty { raw.trim() }, quality = quality)
    }

    private fun canonicalQuality(token: String): String =
        cleanToken(token).uppercase(Locale.ROOT)

    private fun cleanToken(token: String): String =
        token.trim().trim('[', ']', '(', ')', '-', '|', ':').lowercase(Locale.ROOT)

    private fun isQualityToken(token: String): Boolean {
        val cleaned = cleanToken(token)
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
