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
