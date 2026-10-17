package com.dev.Tvivo.ui.browse

import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.home.VirtualFolderKey

/**
 * Virtual folders sit at the top of the rail, above the panel's own categories.
 *
 * **They are modelled as [CategoryEntity] on purpose.** The rail, the header, the counts
 * and the focus contract are all written against a category, and a parallel "virtual
 * row" type would mean every one of them growing a second case. The only thing that
 * makes these different is where their *rows* come from, and that is one `when` in
 * [BrowseViewModel] rather than a second rail.
 *
 * **The id prefix is what keeps them from colliding.** Panel category ids are numeric
 * strings (`"770"`, `"898"`), so a `__` prefix cannot ever be one — and `isVirtual` is a
 * prefix test rather than a set membership test so that adding another folder does not
 * mean remembering to update a list somewhere else.
 */
enum class VirtualFolder(val id: String, val title: String) {

    /** Paged across the whole type; displayed first for one-step access from the rail. */
    ALL("__all", "ALL"),

    /**
     * **The opening selection, deliberately.** ALL leads the rail for quick access, but
     * Recently added is what the grid shows on first entry and it has content on a
     * fresh install. Continue watching and Favourites are both empty
     * until the user has done something, and opening on an empty folder makes a full
     * catalog look like a broken app.
     *
     * Backed by the catalog's own `added` timestamp, newest first, catalog-wide rather
     * than per category. [RECENTLY_ADDED_LIMIT] is a product decision, not a query
     * detail, which is why it lives here and not in the DAO.
     */
    RECENTLY_ADDED("__recent", "RECENTLY ADDED"),

    /**
     * Backed by `resume_positions`, already capped at 50 by the DAO and already filtered
     * to >60 s watched and <92 % complete — a folder that fills with finished films is
     * one nobody opens twice.
     */
    CONTINUE_WATCHING("__continue", "CONTINUE WATCHING"),

    /** Live's last opened channels; a channel has no resume position. */
    RECENTLY_PLAYED("__played", "LAST WATCHED"),

    /** Backed by `favourites`. The only folder whose contents the user chose directly. */
    FAVOURITES("__favourites", "FAVOURITES");

    fun toCategory(accountId: String, type: String, ordering: Int) = CategoryEntity(
        accountId = accountId,
        type = type,
        categoryId = id,
        name = title,
        ordering = ordering
    )

    companion object {
        /** Chosen with the user, 2026-09-10. */
        const val RECENTLY_ADDED_LIMIT = 100

        fun isVirtual(categoryId: String?) = categoryId?.startsWith("__") == true

        fun of(categoryId: String?) = entries.firstOrNull { it.id == categoryId }

        /** Android-side adapter for the platform-neutral landing destination key. */
        fun fromKey(key: VirtualFolderKey) = when (key) {
            VirtualFolderKey.ContinueWatching -> CONTINUE_WATCHING
            VirtualFolderKey.RecentlyAdded -> RECENTLY_ADDED
            VirtualFolderKey.RecentlyPlayed -> RECENTLY_PLAYED
            VirtualFolderKey.Favorites -> FAVOURITES
        }

        /**
         * Live has no resume point — a channel is a stream, not a position — so it gets
         * Favourites and Recently added but never Continue watching. Offering a folder
         * that is structurally always empty is worse than not offering it.
         */
        fun forContentType(isLive: Boolean): List<VirtualFolder> =
            if (isLive) listOf(ALL, RECENTLY_ADDED, RECENTLY_PLAYED, FAVOURITES)
            else listOf(ALL, RECENTLY_ADDED, CONTINUE_WATCHING, FAVOURITES)
    }
}
