package com.dev.Tvivo.fixture

import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.remote.CategoryListParser
import com.dev.Tvivo.data.remote.LiveStreamParser
import com.dev.Tvivo.data.remote.SeriesInfoParser
import com.dev.Tvivo.data.remote.SeriesListParser
import com.dev.Tvivo.data.remote.VodInfoParser
import com.dev.Tvivo.data.remote.VodStreamParser
import com.dev.Tvivo.data.remote.XtreamApiClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

/**
 * Calls only the loopback MockWebServer, through the production Retrofit service and parsers.
 * Runs under Robolectric because [VodInfoParser] uses `org.json`, which is an unmocked stub in a
 * plain JVM test (its `runCatching` would silently turn that into a null plot).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XtreamFixtureContractTest {
    private lateinit var server: MockWebServer
    private lateinit var fixture: XtreamFixtureDispatcher

    @Before fun setUp() {
        fixture = XtreamFixtureDispatcher()
        server = MockWebServer().apply {
            dispatcher = fixture
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        fixture.port = server.port
    }

    @After fun tearDown() = server.shutdown()

    private val api get() = XtreamApiClient.serviceFor(
        Credentials("127.0.0.1", server.port, "fixture-user", "fixture-pass")
    )

    @Test fun authReportsServerAndConfigurableAccountLimit() = runTest {
        for (limit in listOf(1, 3)) {
            fixture.maxConnections = limit
            val response = api.authenticate("fixture-user", "fixture-pass")
            assertTrue(response.isSuccessful)
            val auth = requireNotNull(response.body())
            val user = requireNotNull(auth.userInfo)
            assertTrue(user.isAuthenticated)
            assertTrue(user.isActive)
            assertEquals(limit.toString(), user.maxConnections)
            assertEquals("127.0.0.1", auth.serverInfo?.url)
            assertEquals(server.port.toString(), auth.serverInfo?.port)
        }
        assertEquals(401, api.authenticate("wrong", "fixture-pass").code())
    }

    @Test fun allCategoriesAndFullAndScopedListsUseProductionParsers() = runTest {
        val account = "fixture-account"
        val categories = listOf(
            Triple(TYPE_LIVE, api.getLiveCategories("fixture-user", "fixture-pass"), "101"),
            Triple(TYPE_VOD, api.getVodCategories("fixture-user", "fixture-pass"), "201"),
            Triple(TYPE_SERIES, api.getSeriesCategories("fixture-user", "fixture-pass"), "301")
        )
        categories.forEach { (type, response, expectedId) ->
            assertTrue(response.isSuccessful)
            val rows = CategoryListParser.parse(requireNotNull(response.body()).string(), account, type)
            assertEquals(expectedId, rows.single().categoryId)
            assertTrue(rows.single().name.startsWith("Fixture"))
        }
        for (category in listOf(null, "101")) {
            val live = mutableListOf<com.dev.Tvivo.data.local.entities.LiveStreamEntity>()
            LiveStreamParser.parse(
                requireNotNull(api.getLiveStreams("fixture-user", "fixture-pass", category).body()),
                account, 1, onChunk = { live += it }
            )
            assertEquals(1001, live.single().streamId)
            assertEquals("ts", live.single().ext)
        }
        for (category in listOf(null, "201")) {
            val vod = mutableListOf<com.dev.Tvivo.data.local.entities.VodStreamEntity>()
            VodStreamParser.parse(
                requireNotNull(api.getVodStreams("fixture-user", "fixture-pass", category).body()),
                account, 1, onChunk = { vod += it }
            )
            assertEquals(2001, vod.single().streamId)
            assertEquals("mp4", vod.single().containerExtension)
        }
        for (category in listOf(null, "301")) {
            val shows = mutableListOf<com.dev.Tvivo.data.local.entities.SeriesEntity>()
            SeriesListParser.parse(
                requireNotNull(api.getSeries("fixture-user", "fixture-pass", category).body()),
                account, 1, onChunk = { shows += it }
            )
            assertEquals(3001, shows.single().seriesId)
            assertEquals("Fixture Show", shows.single().name)
        }
    }

    @Test fun detailsPreserveStringEpisodeIdAndPreferDurationOverBadSeconds() = runTest {
        val info = requireNotNull(api.getSeriesInfo("fixture-user", "fixture-pass", 3001).body()).string()
        val episode = SeriesInfoParser.parse(info, "fixture-account", 3001).single()
        assertEquals("episode-A1", episode.episodeId)
        assertEquals(2535, episode.durationSecs)
        assertEquals(mapOf(1 to "Fixture Season"), SeriesInfoParser.parseSeasonNames(info))
        val empty = requireNotNull(api.getSeriesInfo("fixture-user", "fixture-pass", 3002).body()).string()
        assertTrue(SeriesInfoParser.parse(empty, "fixture-account", 3002).isEmpty())
        val plot = VodInfoParser.parsePlot(
            requireNotNull(api.getVodInfo("fixture-user", "fixture-pass", 2001).body())
        )
        assertEquals("A synthetic film for parser tests.", plot)
    }

    @Test fun rateLimitIsDeterministicAndResettable() = runTest {
        fixture.rateLimitNext("get_vod_categories")
        val first = api.getVodCategories("fixture-user", "fixture-pass")
        assertEquals(429, first.code())
        assertEquals("2", first.headers()["Retry-After"])
        assertTrue(api.getVodCategories("fixture-user", "fixture-pass").isSuccessful)
        fixture.rateLimitNext("get_vod_categories")
        fixture.resetFaults()
        assertTrue(api.getVodCategories("fixture-user", "fixture-pass").isSuccessful)
    }

    @Test fun fixtureHostsAreAllowlistedAndArtworkIsSynthetic() {
        val files = listOf("auth", "live_categories", "vod_categories", "series_categories",
            "live_streams", "vod_streams", "series", "series_info", "series_info_empty", "vod_info")
        val host = Regex("https?://([^/\\\" ]+)")
        files.forEach { name ->
            val body = XtreamFixtureDispatcher.fixture("$name.json")
            assertFalse(body.contains("{{PASSWORD}}"))
            host.findAll(body).forEach { match ->
                val value = match.groupValues[1].substringBefore(':')
                assertTrue(value == "127.0.0.1" || value == "10.0.2.2" || value.endsWith(".invalid"))
            }
        }
        assertTrue(XtreamFixtureDispatcher.fixture("artwork/poster.svg").contains("<svg"))
    }
}
