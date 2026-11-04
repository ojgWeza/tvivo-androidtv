package com.dev.Tvivo.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpFileSizeProbeTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun headContentLength_returnsExact_withoutRangeRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Length", "12345678"))
        assertEquals(HttpFileSizeProbe.Result.Exact(12_345_678), probe().probe(server.url("/movie/user/pass/42.mkv").toString(), HttpFileSizeProbe.MediaKind.MOVIE))
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals(1, server.requestCount)
    }

    @Test fun missingHeadLength_fallsBackToSingleByteRangeTotal() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-0/9876543").setBody("x"))
        assertEquals(HttpFileSizeProbe.Result.Exact(9_876_543), probe().probe(server.url("/movie/u/p/1.mkv").toString(), HttpFileSizeProbe.MediaKind.MOVIE))
        assertEquals("HEAD", server.takeRequest().method)
        val range = server.takeRequest()
        assertEquals("GET", range.method)
        assertEquals("bytes=0-0", range.getHeader("Range"))
    }

    @Test fun range200_usesContentLength_andClosesBodyWithoutReadingIt() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(200).setBody("unread-media-body").setHeader("Content-Length", "7654321")
            )
        assertEquals(HttpFileSizeProbe.Result.Exact(7_654_321), probe().probe(server.url("/movie/user/secret/1.mkv").toString(), HttpFileSizeProbe.MediaKind.MOVIE))
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun authRateLimitAndServerErrors_areFailures_withoutFallback() = runBlocking {
        listOf(401 to HttpFileSizeProbe.Failure.UNAUTHORIZED,
            403 to HttpFileSizeProbe.Failure.FORBIDDEN,
            429 to HttpFileSizeProbe.Failure.RATE_LIMITED,
            503 to HttpFileSizeProbe.Failure.SERVER).forEach { (status, failure) ->
            server.enqueue(MockResponse().setResponseCode(status))
            val result = probe().probe(server.url("/movie/user/password/1.mkv").toString(), HttpFileSizeProbe.MediaKind.MOVIE)
            assertEquals(HttpFileSizeProbe.Result.Failed(failure), result)
            server.takeRequest()
        }
    }

    @Test fun timeout_isFailed_andDiagnosticsContainNoCredentialUrl() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val url = server.url("/movie/private-user/private-pass/99.mkv").toString()
        val result = probe().probe(url, HttpFileSizeProbe.MediaKind.MOVIE)
        assertEquals(HttpFileSizeProbe.Result.Failed(HttpFileSizeProbe.Failure.TIMEOUT), result)
        assertFalse(result.toString().contains("private-user"))
        assertFalse(result.toString().contains("private-pass"))
    }

    @Test fun malformedPartialRange_isUnknown_notOneByteExact() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-0/*").setBody("x"))
        assertTrue(probe().probe(server.url("/movie/u/p/1.mkv").toString(), HttpFileSizeProbe.MediaKind.MOVIE) is HttpFileSizeProbe.Result.Unknown)
    }

    @Test fun liveUrl_isRejectedWithoutOpeningSocket() = runBlocking {
        val result = probe().probe(server.url("/live/u/p/1.ts").toString(), HttpFileSizeProbe.MediaKind.MOVIE)
        assertTrue(result is HttpFileSizeProbe.Result.Unknown)
        assertEquals(0, server.requestCount)
    }

    private fun probe() = HttpFileSizeProbe(
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        clockMillis = { System.currentTimeMillis() }
    )
}
