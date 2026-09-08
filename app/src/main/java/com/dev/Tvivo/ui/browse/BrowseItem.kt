package com.dev.Tvivo.ui.browse

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
    val extension: String?
)

/**
 * A poster is 2:3 and safe to crop. Channel art is a logo at 16:9 and must be
 * letterboxed on a neutral tile — cropping it to poster shape destroys it.
 */
enum class CardShape { POSTER, CHANNEL }

fun VodStreamEntity.toBrowseItem() = BrowseItem(
    id = streamId,
    title = nameDisplay,
    imageUrl = streamIcon,
    extension = containerExtension
)

fun LiveStreamEntity.toBrowseItem() = BrowseItem(
    id = streamId,
    title = nameDisplay,
    imageUrl = streamIcon,
    extension = ext
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
    extension = null
)
