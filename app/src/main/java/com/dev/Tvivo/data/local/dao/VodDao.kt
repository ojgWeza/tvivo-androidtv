package com.dev.Tvivo.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import kotlinx.coroutines.flow.Flow

/**
 * Every list query orders by `nameNormalized` — alphabetical, plain `BINARY` collation
 * on an already-lowercased, diacritics-stripped column. The panel's native `num` order
 * is arbitrary to a viewer; see `docs/decisions.md`.
 */
@Dao
interface VodDao {

    @Query(
        """
        SELECT * FROM vod_streams
        WHERE accountId = :accountId AND categoryId = :categoryId
        ORDER BY nameNormalized ASC
        """
    )
    fun pagingInCategory(accountId: String, categoryId: String): PagingSource<Int, VodStreamEntity>

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
        SELECT * FROM vod_streams
        WHERE accountId = :accountId AND categoryId = :categoryId
          AND nameNormalized LIKE '%' || :query || '%'
        ORDER BY nameNormalized ASC
        """
    )
    fun pagingInCategoryFiltered(
        accountId: String,
        categoryId: String,
        query: String
    ): PagingSource<Int, VodStreamEntity>

    /** The `N` of the header's `N of M`. Counted, not taken from the paging list, whose
     *  size is unknown until it has all been loaded. */
    @Query(
        """
        SELECT COUNT(*) FROM vod_streams
        WHERE accountId = :accountId AND categoryId = :categoryId
          AND nameNormalized LIKE '%' || :query || '%'
        """
    )
    fun countInCategoryFiltered(
        accountId: String,
        categoryId: String,
        query: String
    ): Flow<Int>

    /** `ALL`: every row of the type, still alphabetical. */
    @Query("SELECT * FROM vod_streams WHERE accountId = :accountId ORDER BY nameNormalized ASC")
    fun pagingAll(accountId: String): PagingSource<Int, VodStreamEntity>

    @Query(
        """
        SELECT * FROM vod_streams
        WHERE accountId = :accountId AND nameNormalized LIKE '%' || :query || '%'
        ORDER BY nameNormalized ASC
        """
    )
    fun pagingSearch(accountId: String, query: String): PagingSource<Int, VodStreamEntity>

    /**
     * `Recently added`. `added` is a unix timestamp the panel sets, so this is the
     * catalog's own idea of new rather than ours. Rows with a null `added` sort last
     * under `DESC`, which is right: an item that will not say when it arrived has no
     * claim on a folder about arrival order.
     *
     * The limit is the caller's — it is a product decision (100), not a query detail.
     */
    @Query(
        """
        SELECT * FROM vod_streams
        WHERE accountId = :accountId AND added IS NOT NULL
        ORDER BY added DESC LIMIT :limit
        """
    )
    fun recentlyAdded(accountId: String, limit: Int): Flow<List<VodStreamEntity>>

    /**
     * Rail counts, one grouped query exposed as a Flow so they fill in live as the
     * background sync lands.
     */
    @Query(
        """
        SELECT categoryId AS categoryId, COUNT(*) AS count FROM vod_streams
        WHERE accountId = :accountId GROUP BY categoryId
        """
    )
    fun countsByCategory(accountId: String): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM vod_streams WHERE accountId = :accountId")
    fun totalCount(accountId: String): Flow<Int>

    @Query("SELECT * FROM vod_streams WHERE accountId = :accountId AND streamId = :streamId")
    suspend fun byId(accountId: String, streamId: Int): VodStreamEntity?

    /**
     * Continue watching and Favourites hold ids, not rows — the row they point at can be
     * re-synced, renamed or removed underneath them. Resolving them here means a folder
     * whose item has left the catalog simply shows one fewer card rather than a blank.
     *
     * Unordered on purpose: SQL cannot express "in the order these ids were given", and
     * both callers have an order that matters (last-watched, and when it was favourited).
     * The caller re-orders.
     */
    @Query("SELECT * FROM vod_streams WHERE accountId = :accountId AND streamId IN (:ids)")
    suspend fun byIds(accountId: String, ids: List<Int>): List<VodStreamEntity>

    /**
     * Caches a plot fetched from `get_vod_info`. Written straight onto the catalog row so
     * that re-opening a film costs nothing, and so a film the user has looked at once
     * still describes itself with the panel unreachable.
     *
     * It is lost on the next full sync, which rewrites every row — acceptable, because
     * the fetch is one small call and only happens for films actually opened.
     */
    @Query("UPDATE vod_streams SET plot = :plot WHERE accountId = :accountId AND streamId = :streamId")
    suspend fun updatePlot(accountId: String, streamId: Int, plot: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<VodStreamEntity>)

    @Query("DELETE FROM vod_streams WHERE accountId = :accountId AND categoryId = :categoryId")
    suspend fun deleteCategory(accountId: String, categoryId: String)

    @Query("DELETE FROM vod_streams WHERE accountId = :accountId AND generation <= :generation")
    suspend fun deleteGenerationsUpTo(accountId: String, generation: Long)

    /**
     * The single-transaction delete-then-insert is right for a 400-item category and
     * wrong for 48,751 rows — the full-catalog path uses generations instead.
     */
    @Transaction
    suspend fun replaceCategory(accountId: String, categoryId: String, rows: List<VodStreamEntity>) {
        deleteCategory(accountId, categoryId)
        rows.chunked(500).forEach { insertAll(it) }
    }
}

data class CategoryCount(val categoryId: String?, val count: Int)
