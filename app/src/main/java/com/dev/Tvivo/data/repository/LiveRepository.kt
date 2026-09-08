package com.dev.Tvivo.data.repository

import androidx.paging.PagingSource
import com.dev.Tvivo.auth.AppErrorException
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.CATEGORY_LIST_SENTINEL
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.data.local.entities.LiveStreamEntity
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.remote.CategoryListParser
import com.dev.Tvivo.data.remote.ErrorMapper
import com.dev.Tvivo.data.remote.LiveStreamParser
import com.dev.Tvivo.data.remote.XtreamApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * [VodRepository] for live channels. Deliberately the same shape line for line: the TTL
 * and transaction mechanism stays in [CachedFetch], and only the endpoint and the row
 * type differ. Series copies this again in Phase 4.
 */
class LiveRepository(
    private val db: AppDatabase,
    private val credentials: Credentials,
    private val accountId: String
) {
    private val api = XtreamApiClient.serviceFor(credentials)
    private val cache = CachedFetch(db.syncMetaDao())

    fun observeCategories(): Flow<List<CategoryEntity>> =
        db.categoryDao().observe(accountId, TYPE_LIVE)

    fun countsByCategory() = db.liveDao().countsByCategory(accountId)

    fun pagingInCategory(categoryId: String): PagingSource<Int, LiveStreamEntity> =
        db.liveDao().pagingInCategory(accountId, categoryId)

    /**
     * D-14. The query goes through `NameNormalizer.normalizeQuery` here, in one place.
     * `nameNormalized` is lowercased, diacritic-stripped and whitespace-collapsed, so raw
     * user input compared against it silently matches nothing as soon as anyone types a
     * capital or an accent.
     */
    fun pagingInCategoryFiltered(
        categoryId: String,
        query: String
    ): PagingSource<Int, LiveStreamEntity> =
        db.liveDao().pagingInCategoryFiltered(accountId, categoryId, NameNormalizer.normalizeQuery(query))

    fun countInCategoryFiltered(categoryId: String, query: String): Flow<Int> =
        db.liveDao().countInCategoryFiltered(accountId, categoryId, NameNormalizer.normalizeQuery(query))

    fun pagingAll(): PagingSource<Int, LiveStreamEntity> = db.liveDao().pagingAll(accountId)

    fun pagingSearch(query: String): PagingSource<Int, LiveStreamEntity> =
        db.liveDao().pagingSearch(accountId, NameNormalizer.normalizeQuery(query))

    suspend fun byId(streamId: Int): LiveStreamEntity? = db.liveDao().byId(accountId, streamId)

    suspend fun refreshCategories(force: Boolean = false): Result<Boolean> =
        withContext(Dispatchers.IO) {
            cache.ensureFresh(
                accountId = accountId,
                contentType = TYPE_LIVE,
                categoryId = CATEGORY_LIST_SENTINEL,
                force = force,
                fetch = {
                    val response = api.getLiveCategories(credentials.username, credentials.password)
                    if (!response.isSuccessful) {
                        throw AppErrorException(ErrorMapper.fromHttpCode(response.code()))
                    }
                    CategoryListParser.parse(
                        response.body()?.string().orEmpty(),
                        accountId,
                        TYPE_LIVE
                    )
                },
                write = { rows -> db.categoryDao().replaceAll(accountId, TYPE_LIVE, rows) }
            )
        }

    suspend fun refreshCategory(categoryId: String, force: Boolean = false): Result<Boolean> =
        withContext(Dispatchers.IO) {
            cache.ensureFresh(
                accountId = accountId,
                contentType = TYPE_LIVE,
                categoryId = categoryId,
                force = force,
                fetch = {
                    val response = api.getLiveStreams(
                        credentials.username,
                        credentials.password,
                        categoryId
                    )
                    if (!response.isSuccessful) {
                        throw AppErrorException(ErrorMapper.fromHttpCode(response.code()))
                    }
                    val rows = ArrayList<LiveStreamEntity>()
                    response.body()?.let { body ->
                        LiveStreamParser.parse(body, accountId, generation = 0) { chunk ->
                            rows.addAll(chunk)
                        }
                    }
                    rows
                },
                write = { rows -> db.liveDao().replaceCategory(accountId, categoryId, rows) }
            )
        }
}
