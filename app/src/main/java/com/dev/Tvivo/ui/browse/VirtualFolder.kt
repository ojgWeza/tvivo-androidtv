package com.dev.Tvivo.ui.browse

import com.dev.Tvivo.data.local.entities.CategoryEntity

/**
 * The three folders the panel does not publish: `CONTINUE WATCHING`, `FAVOURITES` and
 * `RECENTLY ADDED`. They sit at the top of the rail, above the panel's own categories.
 *
 * **They are modelled as [CategoryEntity] on purpose.** The rail, the header, the counts
 * and the focus contract are all written against a category, and a parallel "virtual
 * row" type would mean every one of them growing a second case. The only thing that
 * makes these different is where their *rows* come from, and that is one `when` in
 * [BrowseViewModel] rather than a second rail.
 *
 * **The id prefix is what keeps them from colliding.** Panel category ids are numeric
 * strings (`"770"`, `"898"`), so a `__` prefix cannot ever be one — and `isVirtual` is a
 * prefix test rather than a set membership test so that adding a fourth folder does not
 * mean remembering to update a list somewhere else.
 */
enum class VirtualFolder(val id: String, val title: String) {

    /**
     * **First, and deliberately.** The rail opens on its top row, so that row is what a
     * user sees before they have decided anything — and it is the only one of the three
     * with content on a fresh install. Continue watching and Favourites are both empty
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

        /**
         * Live has no resume point — a channel is a stream, not a position — so it gets
         * Favourites and Recently added but never Continue watching. Offering a folder
         * that is structurally always empty is worse than not offering it.
         */
        fun forContentType(isLive: Boolean): List<VirtualFolder> =
            if (isLive) listOf(RECENTLY_ADDED, FAVOURITES) else entries
    }
}
