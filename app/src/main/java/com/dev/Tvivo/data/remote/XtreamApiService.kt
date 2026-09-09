package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.model.AuthResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Exactly the endpoints verified live against a panel — see
 * `docs/xtream-api-reference.md`. Note `get_series`, not `get_series_streams`.
 */
interface XtreamApiService {

    @GET("player_api.php")
    suspend fun authenticate(
        @Query("username") username: String,
        @Query("password") password: String
    ): Response<AuthResponse>

    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories"
    ): Response<okhttp3.ResponseBody>

    @GET("player_api.php")
    suspend fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_categories"
    ): Response<okhttp3.ResponseBody>

    @GET("player_api.php")
    suspend fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_categories"
    ): Response<okhttp3.ResponseBody>

    /**
     * [categoryId] is nullable on purpose: whether this panel answers with no
     * `category_id` is the Phase 0 precondition the full-catalog sync tier rests on.
     *
     * Returns a raw body because a full-catalog response is ~15 MB of JSON and must be
     * stream-parsed from the `BufferedSource`, never materialised into a `List`.
     */
    @GET("player_api.php")
    suspend fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("category_id") categoryId: String? = null,
        @Query("action") action: String = "get_vod_streams"
    ): Response<okhttp3.ResponseBody>

    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("category_id") categoryId: String? = null,
        @Query("action") action: String = "get_live_streams"
    ): Response<okhttp3.ResponseBody>

    @GET("player_api.php")
    suspend fun getSeries(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("category_id") categoryId: String? = null,
        @Query("action") action: String = "get_series"
    ): Response<okhttp3.ResponseBody>

    /**
     * A single film's metadata. **This is the only way to get a movie's description on
     * this panel** — verified 2026-09-10: a forced full re-sync produced 0 plots across
     * 48,780 `get_vod_streams` rows, while `get_series` returned 7,604 through the same
     * parser. The list call simply does not carry one for VOD.
     *
     * One small object, so unlike the list endpoints it is safe to materialise.
     */
    @GET("player_api.php")
    suspend fun getVodInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("vod_id") vodId: Int,
        @Query("action") action: String = "get_vod_info"
    ): Response<okhttp3.ResponseBody>

    @GET("player_api.php")
    suspend fun getSeriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("series_id") seriesId: Int,
        @Query("action") action: String = "get_series_info"
    ): Response<okhttp3.ResponseBody>
}
