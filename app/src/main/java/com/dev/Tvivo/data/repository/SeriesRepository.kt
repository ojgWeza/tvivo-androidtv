package com.dev.Tvivo.data.repository

import androidx.paging.PagingSource
import com.dev.Tvivo.auth.AppErrorException
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.CATEGORY_LIST_SENTINEL
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import com.dev.Tvivo.data.local.entities.SeriesEntity
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.remote.CategoryListParser
import com.dev.Tvivo.data.remote.ErrorMapper
import com.dev.Tvivo.data.remote.SeriesInfoParser
import com.dev.Tvivo.data.remote.SeriesListParser
import com.dev.Tvivo.data.remote.XtreamApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * [VodRepository] for series. The catalog half is the same shape line for line — the TTL
 * and transaction mechanism stays in [CachedFetch], only the endpoint and row type differ.
 *
 * The episode half is what series adds. `get_series_info` is fetched **lazily, per show**
 * (`docs/xtream-api-reference.md`): a category holds hundreds of shows and each info
 * response is large, so fetching them with the category would turn a one-second browse
 * into a multi-megabyte one for episodes nobody has asked to see.
 *
 * Episode freshness reuses the same per-category TTL machinery with the show id standing
 * in for the category id, under a distinct content type so it cannot collide with the
 * catalog stamp for a category that happens to share the number.
 */
class SeriesRepository(
    private val db: AppDatabase,
    private val credentials: Credentials,
    private val accountId: String
) {
    private val api = XtreamApiClient.serviceFor(credentials)
    private val cache = CachedFetch(db.syncMetaDao())

    fun observeCategories(): Flow<List<CategoryEntity>> =
        db.categoryDao().observe(accountId, TYPE_SERIES)

    fun countsByCategory() = db.seriesDao().countsByCategory(accountId)

    fun pagingInCategory(categoryId: String): PagingSource<Int, SeriesEntity> =
        db.seriesDao().pagingInCategory(accountId, categoryId)

    /**
     * D-14. The query goes through `NameNormalizer.normalizeQuery` here, in one place.
     * `nameNormalized` is lowercased, diacritic-stripped and whitespace-collapsed, so raw
     * user input compared against it silently matches nothing as soon as anyone types a
     * capital or an accent.
     */
    fun pagingInCategoryFiltered(
        categoryId: String,
        query: String
    ): PagingSource<Int, SeriesEntity> =
        db.seriesDao().pagingInCategoryFiltered(accountId, categoryId, NameNormalizer.normalizeQuery(query))

    fun countInCategoryFiltered(categoryId: String, query: String): Flow<Int> =
        db.seriesDao().countInCategoryFiltered(accountId, categoryId, NameNormalizer.normalizeQuery(query))

    fun pagingAll(): PagingSource<Int, SeriesEntity> = db.seriesDao().pagingAll(accountId)

    fun pagingSearch(query: String): PagingSource<Int, SeriesEntity> =
        db.seriesDao().pagingSearch(accountId, NameNormalizer.normalizeQuery(query))

    /** `Recently added`, newest first, capped by the caller. */
    fun recentlyAdded(limit: Int): Flow<List<SeriesEntity>> =
        db.seriesDao().recentlyAdded(accountId, limit)

    /** Resolves the ids held by Continue watching and Favourites into rows. */
    suspend fun byIds(ids: List<Int>): List<SeriesEntity> =
        if (ids.isEmpty()) emptyList() else db.seriesDao().byIds(accountId, ids)

    suspend fun byId(seriesId: Int): SeriesEntity? = db.seriesDao().byId(accountId, seriesId)

    fun observeEpisodes(seriesId: Int): Flow<List<EpisodeEntity>> =
        db.seriesDao().observeEpisodes(accountId, seriesId)

    suspend fun refreshCategories(force: Boolean = false): Result<Boolean> =
        withContext(Dispatchers.IO) {
            cache.ensureFresh(
                accountId = accountId,
                contentType = TYPE_SERIES,
                categoryId = CATEGORY_LIST_SENTINEL,
                force = force,
                fetch = {
                    val response =
                        api.getSeriesCategories(credentials.username, credentials.password)
                    if (!response.isSuccessful) {
                        throw AppErrorException(ErrorMapper.fromHttpCode(response.code()))
                    }
                    CategoryListParser.parse(
                        response.body()?.string().orEmpty(),
                        accountId,
                        TYPE_SERIES
                    )
                },
                write = { rows -> db.categoryDao().replaceAll(accountId, TYPE_SERIES, rows) }
            )
        }

    suspend fun refreshCategory(categoryId: String, force: Boolean = false): Result<Boolean> =
        withContext(Dispatchers.IO) {
            cache.ensureFresh(
                accountId = accountId,
                contentType = TYPE_SERIES,
                categoryId = categoryId,
                force = force,
                fetch = {
                    val response = api.getSeries(
                        credentials.username,
                        credentials.password,
                        categoryId
                    )
                    if (!response.isSuccessful) {
                        throw AppErrorException(ErrorMapper.fromHttpCode(response.code()))
                    }
                    val rows = ArrayList<SeriesEntity>()
                    response.body()?.let { body ->
                        SeriesListParser.parse(body, accountId, generation = 0) { chunk ->
                            rows.addAll(chunk)
                        }
                    }
                    rows
                },
                write = { rows -> db.seriesDao().replaceCategory(accountId, categoryId, rows) }
            )
        }

    /**
     * Season and episode list for one show. Cached rows are what the picker renders, so a
     * failure here is not fatal to a show that has been opened before — the caller shows
     * the error next to the cached list rather than instead of it.
     */
    suspend fun refreshSeriesInfo(seriesId: Int, force: Boolean = false): Result<Boolean> =
        withContext(Dispatchers.IO) {
            cache.ensureFresh(
                accountId = accountId,
                contentType = TYPE_SERIES_INFO,
                categoryId = seriesId.toString(),
                force = force,
                fetch = {
                    val response = api.getSeriesInfo(
                        credentials.username,
                        credentials.password,
                        seriesId
                    )
                    if (!response.isSuccessful) {
                        throw AppErrorException(ErrorMapper.fromHttpCode(response.code()))
                    }
                    val json = response.body()?.string().orEmpty()
                    lastSeasonNames = SeriesInfoParser.parseSeasonNames(json)
                    SeriesInfoParser.parse(json, accountId, seriesId)
                },
                write = { rows -> db.seriesDao().replaceEpisodes(accountId, seriesId, rows) }
            )
        }

    /**
     * Season display names from the most recent [refreshSeriesInfo]. Deliberately not a
     * table: they are cosmetic labels for one open show, and a `seasons` table would be a
     * migration and a sync path for text the picker can live without.
     */
    var lastSeasonNames: Map<Int, String> = emptyMap()
        private set

    companion object {
        /**
         * A distinct `sync_meta` content type so a show id can never be mistaken for a
         * category id with the same number.
         */
        const val TYPE_SERIES_INFO = "series_info"
    }
}
