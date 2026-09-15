package com.dev.Tvivo.data.remote

import com.dev.Tvivo.auth.Credentials
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * One client per credential set. Base URLs are normalised here rather than at call
 * sites so scheme/port/IPv6 handling exists in exactly one place.
 *
 * System trust anchors are kept. Cleartext is permitted through
 * `network_security_config.xml` because panels are commonly HTTP on non-standard
 * ports — a trust-all `TrustManager` is never the answer to a bad certificate.
 */
object XtreamApiClient {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun serviceFor(credentials: Credentials): XtreamApiService =
        Retrofit.Builder()
            .baseUrl(credentials.baseUrl().trimEnd('/') + "/")
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(XtreamApiService::class.java)
}
