package com.dev.Tvivo.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import com.dev.Tvivo.data.local.entities.SeriesEntity
import kotlinx.coroutines.flow.Flow

/**
 * [VodDao]'s shape for series shows, down to the generation mechanism — see that file
 * for why the full-catalog path cannot use `replaceCategory`.
 *
 * The episode half has no generation: episodes are fetched lazily for one show at a
 * time, so a show's episode list is small enough for the plain delete-then-insert that
 * is wrong at catalog scale.
 */
@Dao
interface SeriesDao {

    @Query(
        """
        SELECT * FROM series
        WHERE accountId = :accountId AND categoryId = :categoryId
        ORDER BY nameNormalized ASC
        """
    )
    fun pagingInCategory(accountId: String, categoryId: String): PagingSource<Int, SeriesEntity>

    /**
     * D-14 — the item filter, scoped to **one category**.
     *
     * Matching is on `nameNormalized`, the same column the sort uses, so filter and order
     * agree, and the caller must put the query through `NameNormalizer.normalizeQuery`
     * first or nothing will match. Note `nameNormalized` derives from `nameDisplay`,
     * which has the quality token stripped — so typing `HD` does **not** filter by
     * quality, even though the badge on the card says `HD`. That badge is a separate
     * field (`qualityOf`).
     *
     * `LIKE '%' || :query || '%'` cannot use an index and is a scan of the category.
     * That is acceptable because the scope is one category, not the 48k-row catalog, and
     * the caller debounces; a global filter would need FTS.
     */
    @Query(
        """
        SELECT * FROM series
        WHERE accountId = :accountId AND categoryId = :categoryId
          AND nameNormalized LIKE '%' || :query || '%'
        ORDER BY nameNormalized ASC
        """
    )
    fun pagingInCategoryFiltered(
        accountId: String,
        categoryId: String,
        query: String
    ): PagingSource<Int, SeriesEntity>

    /** The `N` of the header's `N of M`. Counted, not taken from the paging list, whose
     *  size is unknown until it has all been loaded. */
    @Query(
        """
        SELECT COUNT(*) FROM series
        WHERE accountId = :accountId AND categoryId = :categoryId
          AND nameNormalized LIKE '%' || :query || '%'
        """
    )
    fun countInCategoryFiltered(
        accountId: String,
        categoryId: String,
        query: String
    ): Flow<Int>

    @Query("SELECT * FROM series WHERE accountId = :accountId ORDER BY nameNormalized ASC")
    fun pagingAll(accountId: String): PagingSource<Int, SeriesEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE accountId = :accountId AND nameNormalized LIKE '%' || :query || '%'
        ORDER BY nameNormalized ASC
        """
    )
    fun pagingSearch(accountId: String, query: String): PagingSource<Int, SeriesEntity>

    @Query(
        """
        SELECT categoryId AS categoryId, COUNT(*) AS count FROM series
        WHERE accountId = :accountId GROUP BY categoryId
        """
    )
    fun countsByCategory(accountId: String): Flow<List<CategoryCount>>

    @Query("SELECT * FROM series WHERE accountId = :accountId AND seriesId = :seriesId")
    suspend fun byId(accountId: String, seriesId: Int): SeriesEntity?

    /** `Recently added`, newest first. See [VodDao.recentlyAdded]. */
    @Query(
        """
        SELECT * FROM series
        WHERE accountId = :accountId AND added IS NOT NULL
        ORDER BY added DESC LIMIT :limit
        """
    )
    fun recentlyAdded(accountId: String, limit: Int): Flow<List<SeriesEntity>>

    /**
     * Continue watching and Favourites hold ids, not rows — the row they point at can be
     * re-synced, renamed or removed underneath them. Resolving them here means a folder
     * whose item has left the catalog simply shows one fewer card rather than a blank.
     *
     * Unordered on purpose: SQL cannot express "in the order these ids were given", and
     * both callers have an order that matters (last-watched, and when it was favourited).
     * The caller re-orders.
     */
    @Query("SELECT * FROM series WHERE accountId = :accountId AND seriesId IN (:ids)")
    suspend fun byIds(accountId: String, ids: List<Int>): List<SeriesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE accountId = :accountId AND categoryId = :categoryId")
    suspend fun deleteCategory(accountId: String, categoryId: String)

    @Query("DELETE FROM series WHERE accountId = :accountId AND generation <= :generation")
    suspend fun deleteGenerationsUpTo(accountId: String, generation: Long)

    @Transaction
    suspend fun replaceCategory(accountId: String, categoryId: String, rows: List<SeriesEntity>) {
        deleteCategory(accountId, categoryId)
        rows.chunked(500).forEach { insertAll(it) }
    }

    // --- episodes ------------------------------------------------------------

    /**
     * Ordered by season then episode number, which is the order the picker renders and
     * the order `episode_num` is meaningful in — unlike the catalog lists, alphabetical
     * would be actively wrong here.
     */
    @Query(
        """
        SELECT * FROM series_episodes
        WHERE accountId = :accountId AND seriesId = :seriesId
        ORDER BY seasonNumber ASC, episodeNum ASC
        """
    )
    fun observeEpisodes(accountId: String, seriesId: Int): Flow<List<EpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(rows: List<EpisodeEntity>)

    @Query("DELETE FROM series_episodes WHERE accountId = :accountId AND seriesId = :seriesId")
    suspend fun deleteEpisodes(accountId: String, seriesId: Int)

    /**
     * Delete-then-insert so an episode the panel dropped disappears. Guarded against the
     * empty case: `get_series_info` answering with an error object parses to zero
     * episodes, and wiping a cached season on that would make an offline show unplayable.
     */
    @Transaction
    suspend fun replaceEpisodes(accountId: String, seriesId: Int, rows: List<EpisodeEntity>) {
        if (rows.isEmpty()) return
        deleteEpisodes(accountId, seriesId)
        rows.chunked(500).forEach { insertEpisodes(it) }
    }
}
