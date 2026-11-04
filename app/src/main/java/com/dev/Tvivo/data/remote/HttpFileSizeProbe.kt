package com.dev.Tvivo.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Header-only size lookup for progressive movie/episode URLs. Caller must own the media slot. */
class HttpFileSizeProbe(
    private val client: OkHttpClient = OkHttpClient(),
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    sealed interface Result {
        data class Exact(val bytes: Long) : Result
        data class Unknown(val reason: Reason) : Result
        data class Failed(val kind: Failure) : Result
    }

    enum class Reason { NO_SIZE_HEADER, INVALID_SIZE_HEADER, UNSUPPORTED_RESPONSE }
    enum class Failure { UNAUTHORIZED, FORBIDDEN, RATE_LIMITED, SERVER, TIMEOUT, NETWORK }
    enum class MediaKind { MOVIE, EPISODE }

    suspend fun probe(streamUrl: String, kind: MediaKind): Result = withContext(Dispatchers.IO) {
        val parsedUrl = streamUrl.toHttpUrlOrNull()
            ?: return@withContext Result.Unknown(Reason.UNSUPPORTED_RESPONSE)
        val expectedSegment = if (kind == MediaKind.MOVIE) "movie" else "series"
        if (expectedSegment !in parsedUrl.encodedPathSegments) {
            return@withContext Result.Unknown(Reason.UNSUPPORTED_RESPONSE)
        }
        val started = clockMillis()
        try {
            val head = request(streamUrl, "HEAD", started)
            val headResult = head.use { response ->
                responseFailure(response.code)?.let { return@withContext Result.Failed(it) }
                val size = response.header("Content-Length")?.toLongOrNull()?.takeIf(::validSize)
                    ?: response.header("Content-Range")?.let(::contentRangeTotal)
                size?.let { Result.Exact(it) }
            }
            if (headResult != null) return@withContext headResult

            val ranged = request(streamUrl, "GET", started, range = true)
            ranged.use { response ->
                responseFailure(response.code)?.let { return@withContext Result.Failed(it) }
                when (response.code) {
                    206 -> response.header("Content-Range")?.let(::contentRangeTotal)?.let(Result::Exact)
                        ?: Result.Unknown(Reason.INVALID_SIZE_HEADER)
                    200 -> response.header("Content-Length")?.toLongOrNull()?.takeIf(::validSize)?.let(Result::Exact)
                        ?: Result.Unknown(Reason.INVALID_SIZE_HEADER)
                    else -> Result.Unknown(Reason.UNSUPPORTED_RESPONSE)
                }
            }
        } catch (_: InterruptedIOException) {
            Result.Failed(Failure.TIMEOUT)
        } catch (_: IOException) {
            Result.Failed(if (clockMillis() - started >= TIMEOUT_MS) Failure.TIMEOUT else Failure.NETWORK)
        } catch (_: IllegalArgumentException) {
            Result.Unknown(Reason.UNSUPPORTED_RESPONSE)
        }
    }

    private fun request(url: String, method: String, started: Long, range: Boolean = false): Response {
        val remaining = (TIMEOUT_MS - (clockMillis() - started)).coerceAtLeast(1)
        val request = Request.Builder().url(url).method(method, null)
            .apply { if (range) header("Range", "bytes=0-0") }.build()
        val call = client.newCall(request)
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
        return call.execute()
    }

    private fun responseFailure(code: Int): Failure? = when (code) {
        401 -> Failure.UNAUTHORIZED
        403 -> Failure.FORBIDDEN
        429 -> Failure.RATE_LIMITED
        in 500..599 -> Failure.SERVER
        else -> null
    }

    private fun contentRangeTotal(value: String): Long? =
        CONTENT_RANGE.matchEntire(value.trim())?.groupValues?.get(1)?.toLongOrNull()?.takeIf(::validSize)

    private fun validSize(bytes: Long): Boolean = bytes in MIN_BYTES..MAX_BYTES

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val MIN_BYTES = 1_000_000L
        const val MAX_BYTES = 200_000_000_000L
        val CONTENT_RANGE = Regex("bytes\\s+0-0/([0-9]+)", RegexOption.IGNORE_CASE)
    }
}
