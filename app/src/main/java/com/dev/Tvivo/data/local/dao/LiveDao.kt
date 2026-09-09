package com.dev.Tvivo.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.dev.Tvivo.data.local.entities.LiveStreamEntity
import kotlinx.coroutines.flow.Flow

/**
 * [VodDao]'s shape for live channels, down to the generation mechanism — see that file
 * for why the full-catalog path cannot use `replaceCategory`.
 *
 * Ordering is `nameNormalized`, not the panel's `num`, per `docs/decisions.md`
 * ("Category grids sort alphabetically on `name_normalized`") — that decision names
 * Live explicitly.
 */
@Dao
interface LiveDao {

    @Query(
        """
        SELECT * FROM live_streams
        WHERE accountId = :accountId AND categoryId = :categoryId
        ORDER BY nameNormalized ASC
        """
    )
    fun pagingInCategory(accountId: String, categoryId: String): PagingSource<Int, LiveStreamEntity>

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
        SELECT * FROM live_streams
        WHERE accountId = :accountId AND categoryId = :categoryId
          AND nameNormalized LIKE '%' || :query || '%'
        ORDER BY nameNormalized ASC
        """
    )
    fun pagingInCategoryFiltered(
        accountId: String,
        categoryId: String,
        query: String
    ): PagingSource<Int, LiveStreamEntity>

    /** The `N` of the header's `N of M`. Counted, not taken from the paging list, whose
     *  size is unknown until it has all been loaded. */
    @Query(
        """
        SELECT COUNT(*) FROM live_streams
        WHERE accountId = :accountId AND categoryId = :categoryId
          AND nameNormalized LIKE '%' || :query || '%'
        """
    )
    fun countInCategoryFiltered(
        accountId: String,
        categoryId: String,
        query: String
    ): Flow<Int>

    @Query("SELECT * FROM live_streams WHERE accountId = :accountId ORDER BY nameNormalized ASC")
    fun pagingAll(accountId: String): PagingSource<Int, LiveStreamEntity>

    @Query(
        """
        SELECT * FROM live_streams
        WHERE accountId = :accountId AND nameNormalized LIKE '%' || :query || '%'
        ORDER BY nameNormalized ASC
        """
    )
    fun pagingSearch(accountId: String, query: String): PagingSource<Int, LiveStreamEntity>

    @Query(
        """
        SELECT categoryId AS categoryId, COUNT(*) AS count FROM live_streams
        WHERE accountId = :accountId GROUP BY categoryId
        """
    )
    fun countsByCategory(accountId: String): Flow<List<CategoryCount>>

    @Query("SELECT * FROM live_streams WHERE accountId = :accountId AND streamId = :streamId")
    suspend fun byId(accountId: String, streamId: Int): LiveStreamEntity?

    /** `Recently added`, newest first. See [VodDao.recentlyAdded]. */
    @Query(
        """
        SELECT * FROM live_streams
        WHERE accountId = :accountId AND added IS NOT NULL
        ORDER BY added DESC LIMIT :limit
        """
    )
    fun recentlyAdded(accountId: String, limit: Int): Flow<List<LiveStreamEntity>>

    /**
     * Continue watching and Favourites hold ids, not rows — the row they point at can be
     * re-synced, renamed or removed underneath them. Resolving them here means a folder
     * whose item has left the catalog simply shows one fewer card rather than a blank.
     *
     * Unordered on purpose: SQL cannot express "in the order these ids were given", and
     * both callers have an order that matters (last-watched, and when it was favourited).
     * The caller re-orders.
     */
    @Query("SELECT * FROM live_streams WHERE accountId = :accountId AND streamId IN (:ids)")
    suspend fun byIds(accountId: String, ids: List<Int>): List<LiveStreamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<LiveStreamEntity>)

    @Query("DELETE FROM live_streams WHERE accountId = :accountId AND categoryId = :categoryId")
    suspend fun deleteCategory(accountId: String, categoryId: String)

    @Query("DELETE FROM live_streams WHERE accountId = :accountId AND generation <= :generation")
    suspend fun deleteGenerationsUpTo(accountId: String, generation: Long)

    @Transaction
    suspend fun replaceCategory(
        accountId: String,
        categoryId: String,
        rows: List<LiveStreamEntity>
    ) {
        deleteCategory(accountId, categoryId)
        rows.chunked(500).forEach { insertAll(it) }
    }
}
