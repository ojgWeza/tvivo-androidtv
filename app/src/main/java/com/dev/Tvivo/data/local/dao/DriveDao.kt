package com.dev.Tvivo.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dev.Tvivo.data.local.entities.DriveEpisodeEntity
import com.dev.Tvivo.data.local.entities.DriveIndexStateEntity
import com.dev.Tvivo.data.local.entities.DriveMovieEntity
import com.dev.Tvivo.data.local.entities.DriveSeasonEntity
import com.dev.Tvivo.data.local.entities.DriveShowEntity
import com.dev.Tvivo.data.local.entities.MusicTrackEntity
import kotlinx.coroutines.flow.Flow

/** Every public read resolves the committed generation, never an uncommitted replacement. */
@Dao
interface DriveDao {
    @Query("SELECT * FROM drive_index_state WHERE accountId = :accountId AND kind = :kind")
    suspend fun state(accountId: String, kind: String): DriveIndexStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(row: DriveIndexStateEntity)

    @Insert suspend fun insertMovies(rows: List<DriveMovieEntity>)
    @Insert suspend fun insertShows(rows: List<DriveShowEntity>)
    @Insert suspend fun insertSeasons(rows: List<DriveSeasonEntity>)
    @Insert suspend fun insertEpisodes(rows: List<DriveEpisodeEntity>)
    @Insert suspend fun insertMusic(rows: List<MusicTrackEntity>)

    @Query("DELETE FROM drive_movies WHERE accountId = :accountId AND generation != :generation")
    suspend fun pruneMovies(accountId: String, generation: Long)
    @Query("DELETE FROM drive_shows WHERE accountId = :accountId AND generation != :generation")
    suspend fun pruneShows(accountId: String, generation: Long)
    @Query("DELETE FROM music_tracks WHERE accountId = :accountId AND generation != :generation")
    suspend fun pruneMusic(accountId: String, generation: Long)

    @Query("""SELECT * FROM drive_movies WHERE accountId = :accountId AND id = :id
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'movies')""")
    suspend fun movie(accountId: String, id: String): DriveMovieEntity?
    @Query("""SELECT * FROM drive_shows WHERE accountId = :accountId AND id = :id
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'series')""")
    suspend fun show(accountId: String, id: String): DriveShowEntity?
    @Query("""SELECT * FROM drive_episodes WHERE accountId = :accountId AND id = :id
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'series')""")
    suspend fun episode(accountId: String, id: String): DriveEpisodeEntity?
    @Query("""SELECT * FROM music_tracks WHERE accountId = :accountId AND id = :id
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'music')""")
    suspend fun track(accountId: String, id: String): MusicTrackEntity?

    @Query("""SELECT * FROM drive_seasons WHERE accountId = :accountId AND showId = :showId
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'series')
        ORDER BY sortOrder, seasonNumber""")
    fun seasons(accountId: String, showId: String): Flow<List<DriveSeasonEntity>>
    @Query("""SELECT * FROM drive_episodes WHERE accountId = :accountId AND showId = :showId
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'series')
        ORDER BY seasonNumber, sortOrder""")
    fun episodes(accountId: String, showId: String): Flow<List<DriveEpisodeEntity>>

    @Query("""SELECT * FROM drive_movies WHERE accountId = :accountId
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'movies')
        AND (:categoryId IS NULL OR categoryId = :categoryId)
        AND (:query = '' OR nameNormalized LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY sortOrder, id""")
    fun pageMovies(accountId: String, categoryId: String?, query: String): PagingSource<Int, DriveMovieEntity>
    @Query("""SELECT * FROM drive_shows WHERE accountId = :accountId
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'series')
        AND (:categoryId IS NULL OR categoryId = :categoryId)
        AND (:query = '' OR nameNormalized LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY sortOrder, id""")
    fun pageShows(accountId: String, categoryId: String?, query: String): PagingSource<Int, DriveShowEntity>
    @Query("""SELECT * FROM music_tracks WHERE accountId = :accountId
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'music')
        AND (:categoryId IS NULL OR categoryId = :categoryId)
        AND (:query = '' OR nameNormalized LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY sortOrder, id""")
    fun pageMusic(accountId: String, categoryId: String?, query: String): PagingSource<Int, MusicTrackEntity>

    @Query("""SELECT * FROM drive_movies WHERE accountId = :accountId
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'movies')
        AND nameNormalized LIKE '%' || :query || '%' ESCAPE '\' ORDER BY sortOrder, id LIMIT :limit""")
    suspend fun searchMovies(accountId: String, query: String, limit: Int): List<DriveMovieEntity>
    @Query("""SELECT * FROM drive_shows WHERE accountId = :accountId
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'series')
        AND nameNormalized LIKE '%' || :query || '%' ESCAPE '\' ORDER BY sortOrder, id LIMIT :limit""")
    suspend fun searchShows(accountId: String, query: String, limit: Int): List<DriveShowEntity>
    @Query("""SELECT * FROM music_tracks WHERE accountId = :accountId
        AND generation = (SELECT generation FROM drive_index_state WHERE accountId = :accountId AND kind = 'music')
        AND nameNormalized LIKE '%' || :query || '%' ESCAPE '\' ORDER BY sortOrder, id LIMIT :limit""")
    suspend fun searchMusic(accountId: String, query: String, limit: Int): List<MusicTrackEntity>
}
