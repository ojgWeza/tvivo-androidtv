package com.dev.Tvivo.data.repository

import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.data.local.entities.DriveEpisodeEntity
import com.dev.Tvivo.data.local.entities.DriveIndexStateEntity
import com.dev.Tvivo.data.local.entities.DriveMovieEntity
import com.dev.Tvivo.data.local.entities.DriveSeasonEntity
import com.dev.Tvivo.data.local.entities.DriveShowEntity
import com.dev.Tvivo.data.local.entities.MusicTrackEntity
import com.dev.Tvivo.data.local.entities.TYPE_MUSIC
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.source.SourceId
import com.dev.Tvivo.source.library.MovieEntry
import com.dev.Tvivo.source.library.ParseResult
import com.dev.Tvivo.source.library.ParsedList
import com.dev.Tvivo.source.library.ShowEntry
import com.dev.Tvivo.source.library.SongEntry
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

/** Local Drive index projection. No Drive client, auth, player or Xtream dispatch lives here. */
class DriveRepository(private val db: AppDatabase, sourceId: SourceId) {
    private val accountId = sourceId.value.also { require(it.startsWith("drive:")) }
    private val dao = db.driveDao()

    /** Parsed files are applied independently; a rejected file cannot change Room state. */
    suspend fun applyMovies(result: ParseResult<ParsedList<MovieEntry>>, syncedAt: Long): Boolean {
        val list = (result as? ParseResult.Valid)?.items ?: return false
        require(list.kind == "movies")
        db.withTransaction {
            val generation = nextGeneration("movies")
            dao.insertMovies(list.items.mapIndexed { order, item ->
                DriveMovieEntity(accountId, item.id, categoryId(TYPE_VOD, item.group), item.title,
                    item.title, NameNormalizer.normalizeQuery(item.title), item.streamUrl,
                    item.posterUrl, item.year, item.plot, order, generation)
            })
            replaceCategories(TYPE_VOD, list.items.map { it.group })
            dao.upsertState(stateFor("movies", generation, syncedAt))
            dao.pruneMovies(accountId, generation)
        }
        return true
    }

    suspend fun applySeries(result: ParseResult<ParsedList<ShowEntry>>, syncedAt: Long): Boolean {
        val list = (result as? ParseResult.Valid)?.items ?: return false
        require(list.kind == "series")
        db.withTransaction {
            val generation = nextGeneration("series")
            val shows = mutableListOf<DriveShowEntity>()
            val seasons = mutableListOf<DriveSeasonEntity>()
            val episodes = mutableListOf<DriveEpisodeEntity>()
            list.items.forEachIndexed { showOrder, show ->
                shows += DriveShowEntity(accountId, show.id, categoryId(TYPE_SERIES, show.group),
                    show.title, show.title, NameNormalizer.normalizeQuery(show.title),
                    show.posterUrl, show.year, show.plot, showOrder, generation)
                show.seasons.forEachIndexed { seasonOrder, season ->
                    seasons += DriveSeasonEntity(accountId, show.id, season.number, season.title,
                        seasonOrder, generation)
                    season.episodes.forEachIndexed { episodeOrder, episode ->
                        episodes += DriveEpisodeEntity(accountId, episode.id, show.id, season.number,
                            episode.number, episode.title, episode.title,
                            NameNormalizer.normalizeQuery(episode.title), episode.streamUrl,
                            episode.posterUrl ?: show.posterUrl, episode.plot ?: show.plot,
                            episodeOrder, generation)
                    }
                }
            }
            dao.insertShows(shows)
            dao.insertSeasons(seasons)
            dao.insertEpisodes(episodes)
            replaceCategories(TYPE_SERIES, list.items.map { it.group })
            dao.upsertState(stateFor("series", generation, syncedAt))
            dao.pruneShows(accountId, generation)
        }
        return true
    }

    suspend fun applyMusic(result: ParseResult<ParsedList<SongEntry>>, syncedAt: Long): Boolean {
        val list = (result as? ParseResult.Valid)?.items ?: return false
        require(list.kind == "music")
        db.withTransaction {
            val generation = nextGeneration("music")
            dao.insertMusic(list.items.mapIndexed { order, item ->
                MusicTrackEntity(accountId, item.id, categoryId(TYPE_MUSIC, item.group), item.title,
                    item.title, NameNormalizer.normalizeQuery(item.title), item.streamUrl,
                    item.artist, item.album, item.artworkUrl, item.year, item.durationSeconds,
                    order, generation)
            })
            replaceCategories(TYPE_MUSIC, list.items.map { it.group })
            dao.upsertState(stateFor("music", generation, syncedAt))
            dao.pruneMusic(accountId, generation)
        }
        return true
    }

    private suspend fun nextGeneration(kind: String): Long =
        Math.addExact(dao.state(accountId, kind)?.generation ?: 0L, 1L)

    private suspend fun stateFor(kind: String, generation: Long, syncedAt: Long): DriveIndexStateEntity =
        (dao.state(accountId, kind) ?: DriveIndexStateEntity(accountId, kind))
            .copy(generation = generation, lastSuccessfulSyncAt = syncedAt)

    private suspend fun replaceCategories(type: String, groups: List<String>) {
        db.categoryDao().replaceAll(accountId, type, groups.distinct().mapIndexed { order, group ->
            CategoryEntity(accountId, type, categoryId(type, group), group, order)
        })
    }

    fun categories(type: String): Flow<List<CategoryEntity>> = db.categoryDao().observe(accountId, type)
    fun pageMovies(categoryId: String? = null, query: String = ""): PagingSource<Int, DriveMovieEntity> =
        dao.pageMovies(accountId, categoryId, NameNormalizer.escapeLikeQuery(query))
    fun pageShows(categoryId: String? = null, query: String = ""): PagingSource<Int, DriveShowEntity> =
        dao.pageShows(accountId, categoryId, NameNormalizer.escapeLikeQuery(query))
    fun pageMusic(categoryId: String? = null, query: String = ""): PagingSource<Int, MusicTrackEntity> =
        dao.pageMusic(accountId, categoryId, NameNormalizer.escapeLikeQuery(query))
    suspend fun movie(id: String) = dao.movie(accountId, id)
    suspend fun show(id: String) = dao.show(accountId, id)
    suspend fun episode(id: String) = dao.episode(accountId, id)
    suspend fun track(id: String) = dao.track(accountId, id)
    fun seasons(showId: String) = dao.seasons(accountId, showId)
    fun episodes(showId: String) = dao.episodes(accountId, showId)
    suspend fun searchMovies(query: String, limit: Int = 20) =
        dao.searchMovies(accountId, NameNormalizer.escapeLikeQuery(query), limit)
    suspend fun searchShows(query: String, limit: Int = 20) =
        dao.searchShows(accountId, NameNormalizer.escapeLikeQuery(query), limit)
    suspend fun searchMusic(query: String, limit: Int = 20) =
        dao.searchMusic(accountId, NameNormalizer.escapeLikeQuery(query), limit)

    companion object {
        fun categoryId(type: String, group: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest("$type\u0000$group".toByteArray(Charsets.UTF_8))
            return "drive-group:" + bytes.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}

/** String-keyed card projection for the later source-aware browse route. */
data class DriveBrowseItem(val id: String, val title: String, val imageUrl: String?,
    val directUrl: String?, val knownDurationMs: Long = 0)

fun DriveMovieEntity.toDriveBrowseItem() = DriveBrowseItem(id, nameDisplay, posterUrl, directUrl)
fun DriveShowEntity.toDriveBrowseItem() = DriveBrowseItem(id, nameDisplay, posterUrl, null)
fun MusicTrackEntity.toDriveBrowseItem() = DriveBrowseItem(id, nameDisplay, artworkUrl, directUrl,
    durationSeconds?.toLong()?.times(1000) ?: 0)
