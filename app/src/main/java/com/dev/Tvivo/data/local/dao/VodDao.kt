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

    @Query("SELECT * FROM vod_streams WHERE accountId = :accountId ORDER BY added DESC LIMIT :limit")
    fun recentlyAdded(accountId: String, limit: Int = 30): Flow<List<VodStreamEntity>>

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
