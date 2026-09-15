package com.dev.Tvivo.ui.browse

import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.LiveStreamEntity
import com.dev.Tvivo.data.local.entities.SeriesEntity
import com.dev.Tvivo.data.local.entities.VodStreamEntity

/**
 * What the grid renders, for every content type.
 *
 * Phase 2 typed the grid directly to [VodStreamEntity], which meant Live could only
 * reuse it by copying it. The four things `ContentGrid` carries — placeholders with
 * stable keys, focus restoration by item ID, the focus frame, the long-press menu — are
 * exactly the things that must not be re-implemented per type, so the grid is typed to
 * this instead and each repository maps into it.
 */
data class BrowseItem(
    val id: Int,
    val title: String,
    val imageUrl: String?,
    /** `container_extension` for movies/episodes, `ext` for live, null for a show. */
    val extension: String?,
    /**
     * `"HD"`, `"SD"`, `"4K"` — the quality token stripped out of [title], or null.
     *
     * The panel publishes the same show at several qualities as separate rows. Without
     * this the grid renders both as the same string and they read as duplicates.
     */
    val quality: String? = null,
    /**
     * Panel rating on a 0–10 scale, or null when the panel did not rate it.
     *
     * Null is the common case on this panel and the badge simply does not draw — an
     * "unrated" placeholder on most of a 48,780-row catalog would be noise, not
     * information. Live carries none: a channel is not a title.
     */
    val rating: Double? = null
)

/**
 * A poster is 2:3 and safe to crop. Channel art is a logo at 16:9 and must be
 * letterboxed on a neutral tile — cropping it to poster shape destroys it.
 */
enum class CardShape { POSTER, CHANNEL }

/**
 * `7.4` — one decimal, no `/10` suffix and no star glyph.
 *
 * The scale is not written out because the badge has to survive at card size, and a
 * single number in a fixed corner reads as a rating without being told. A glyph was the
 * other option and was dropped for the reason Q-16 records: the TV font stack cannot be
 * relied on for symbols, and a missing star draws as tofu.
 *
 * Locale-independent on purpose (`Locale.ROOT`): this is a number, and an Arabic locale
 * rendering it in Eastern Arabic numerals beside Latin catalog titles reads as a glitch.
 */
fun Double.asRatingLabel(): String = if (this % 1.0 == 0.0) {
    String.format(java.util.Locale.ROOT, "%.0f", this)
} else {
    String.format(java.util.Locale.ROOT, "%.1f", this)
}

fun VodStreamEntity.toBrowseItem() = BrowseItem(
    id = streamId,
    title = nameDisplay,
    imageUrl = streamIcon,
    extension = containerExtension,
    quality = NameNormalizer.qualityOf(name),
    rating = rating
)

fun LiveStreamEntity.toBrowseItem() = BrowseItem(
    id = streamId,
    title = nameDisplay,
    imageUrl = streamIcon,
    extension = ext,
    quality = NameNormalizer.qualityOf(name)
)

/**
 * A show is not playable — `series_id` addresses no stream endpoint — so it carries no
 * extension, and activating it opens the season/episode picker instead of the player.
 * The grid does not need to know that; `MainActivity` routes on the content type.
 */
fun SeriesEntity.toBrowseItem() = BrowseItem(
    id = seriesId,
    title = nameDisplay,
    imageUrl = streamIcon,
    extension = null,
    quality = NameNormalizer.qualityOf(name),
    rating = rating
)
