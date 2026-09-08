package com.dev.Tvivo.data.repository

import androidx.paging.PagingSource
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.CATEGORY_LIST_SENTINEL
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import com.dev.Tvivo.data.remote.CategoryListParser
import com.dev.Tvivo.data.remote.ErrorMapper
import com.dev.Tvivo.data.remote.VodStreamParser
import com.dev.Tvivo.data.remote.XtreamApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Thin by design: field mapping and playback rules only. The TTL and transaction
 * mechanism lives in [CachedFetch], so live and series can copy this shape exactly.
 */
class VodRepository(
    private val db: AppDatabase,
    private val credentials: Credentials,
    private val accountId: String
) {
    private val api = XtreamApiClient.serviceFor(credentials)
    private val cache = CachedFetch(db.syncMetaDao())

    fun observeCategories(): Flow<List<CategoryEntity>> =
        db.categoryDao().observe(accountId, TYPE_VOD)

    fun countsByCategory() = db.vodDao().countsByCategory(accountId)

    fun pagingInCategory(categoryId: String): PagingSource<Int, VodStreamEntity> =
        db.vodDao().pagingInCategory(accountId, categoryId)

    /**
     * D-14. The query goes through `NameNormalizer.normalizeQuery` here, in one place.
     * `nameNormalized` is lowercased, diacritic-stripped and whitespace-collapsed, so raw
     * user input compared against it silently matches nothing as soon as anyone types a
     * capital or an accent.
     */
    fun pagingInCategoryFiltered(
        categoryId: String,
        query: String
    ): PagingSource<Int, VodStreamEntity> =
        db.vodDao().pagingInCategoryFiltered(accountId, categoryId, NameNormalizer.normalizeQuery(query))

    fun countInCategoryFiltered(categoryId: String, query: String): Flow<Int> =
        db.vodDao().countInCategoryFiltered(accountId, categoryId, NameNormalizer.normalizeQuery(query))

    fun pagingAll(): PagingSource<Int, VodStreamEntity> = db.vodDao().pagingAll(accountId)

    fun pagingSearch(query: String): PagingSource<Int, VodStreamEntity> =
        db.vodDao().pagingSearch(accountId, NameNormalizer.normalizeQuery(query))

    suspend fun byId(streamId: Int): VodStreamEntity? = db.vodDao().byId(accountId, streamId)

    /**
     * Categories are one fast call, which is what makes a content type browsable in about
     * a second while the full catalog syncs behind it.
     */
    suspend fun refreshCategories(force: Boolean = false): Result<Boolean> =
        withContext(Dispatchers.IO) {
            cache.ensureFresh(
                accountId = accountId,
                contentType = TYPE_VOD,
                categoryId = CATEGORY_LIST_SENTINEL,
                force = force,
                fetch = {
                    val response = api.getVodCategories(credentials.username, credentials.password)
                    if (!response.isSuccessful) {
                        throw com.dev.Tvivo.auth.AppErrorException(
                            ErrorMapper.fromHttpCode(response.code())
                        )
                    }
                    CategoryListParser.parse(
                        response.body()?.string().orEmpty(),
                        accountId,
                        TYPE_VOD
                    )
                },
                write = { rows -> db.categoryDao().replaceAll(accountId, TYPE_VOD, rows) }
            )
        }

    /**
     * One category at a time: delete only that category's rows and insert the new ones in
     * a single transaction, so a mid-fetch failure never leaves a half-written category.
     */
    suspend fun refreshCategory(categoryId: String, force: Boolean = false): Result<Boolean> =
        withContext(Dispatchers.IO) {
            cache.ensureFresh(
                accountId = accountId,
                contentType = TYPE_VOD,
                categoryId = categoryId,
                force = force,
                fetch = {
                    val response = api.getVodStreams(
                        credentials.username,
                        credentials.password,
                        categoryId
                    )
                    if (!response.isSuccessful) {
                        throw com.dev.Tvivo.auth.AppErrorException(
                            ErrorMapper.fromHttpCode(response.code())
                        )
                    }
                    val rows = ArrayList<VodStreamEntity>()
                    response.body()?.let { body ->
                        VodStreamParser.parse(body, accountId, generation = 0) { chunk ->
                            rows.addAll(chunk)
                        }
                    }
                    rows
                },
                write = { rows -> db.vodDao().replaceCategory(accountId, categoryId, rows) }
            )
        }
}
