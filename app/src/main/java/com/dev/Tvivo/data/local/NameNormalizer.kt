package com.dev.Tvivo.data.local

import com.dev.Tvivo.home.NormalizedNameKey
import com.dev.Tvivo.home.TitleDisplayPolicy

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

    private val ARABIC_DIACRITICS = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Matches a bracket pair with nothing (or only punctuation/whitespace) between them --
     * `()`, `( )`, `[]`, `[-]`. Some panels template titles as `"{title} ({quality})"` and
     * substitute an empty string when quality metadata is missing, leaving the empty
     * parens behind: `"Movie Name ()"`, `"Movie Name () ()"`, or -- combined with the
     * quality-token stripping below -- a title that is *only* `"HD ()"`, which strips down
     * to a bare `"()"` if this isn't handled first.
     */
    private val EMPTY_BRACKETS = Regex("[\\(\\[][\\s\\-:|]*[\\)\\]]")

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

    /** Treat SQL LIKE metacharacters as literal title characters. */
    fun escapeLikeQuery(input: String): String = normalizeQuery(input)
        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

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

        // Empty bracket groups are removed before quality-token stripping runs, not after:
        // "HD ()" with the emptiness left in would strip "HD" as a quality token (the "at
        // least one token remains" guard below only protects the token being stripped, not
        // what's left over) and leave the display as literally "()". Doing it first turns
        // that same input into "HD" -- still not a real title, but not punctuation either --
        // and leaves genuine multi-word titles like "Movie Name () ()" as "Movie Name".
        s = EMPTY_BRACKETS.replace(s, " ")
        s = WHITESPACE.replace(s, " ").trim()

        // Edge-token cleanup is shared with JVM golden tests. The policy preserves a
        // quality-only title and removes a leading token only when a real title remains.
        val clean = TitleDisplayPolicy.clean(s)
        return Stripped(display = clean.display, quality = clean.quality)
    }

    private fun toNormalized(display: String): String {
        return NormalizedNameKey.of(display)
    }
}
