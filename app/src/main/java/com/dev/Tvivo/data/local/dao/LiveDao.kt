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
