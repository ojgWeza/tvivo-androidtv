package com.dev.Tvivo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Series end to end through the layer the other two content types do not have:
 * `get_series_info`, its season-keyed episode object, its own per-show TTL, and the
 * guarantee that a failed refresh cannot take away a show that is already cached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeriesRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repo: SeriesRepository

    private val account = "acct-a"
    private val seriesId = 10840

    private val infoJson = """
        {
          "seasons": [{ "season_number": 1, "name": "Season One" }],
          "episodes": {
            "1": [
              {"id":"533032","episode_num":1,"title":"Ep one","container_extension":"mkv"},
              {"id":"533033","episode_num":2,"title":"Ep two","container_extension":"mkv"}
            ],
            "2": [
              {"id":"533040","episode_num":1,"title":"Ep three","container_extension":"mp4"}
            ]
          }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = SeriesRepository(
            db,
            Credentials(server.hostName, server.port, "u", "p"),
            account
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun refreshCategory_caches_shows_from_get_series() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"series_id":1,"name":"A Show","category_id":770,"cover":"c"}]"""
            )
        )

        assertTrue(repo.refreshCategory("770").getOrThrow())

        val show = repo.byId(1)
        assertEquals("770", show?.categoryId)
        assertEquals("c", show?.streamIcon)

        // The panel is asked for the right action and category, not `get_series_streams`.
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.contains("action=get_series"))
        assertTrue(path, path.contains("category_id=770"))
    }

    @Test
    fun refreshSeriesInfo_caches_every_season_ordered_for_the_picker() = runTest {
        server.enqueue(MockResponse().setBody(infoJson))

        assertTrue(repo.refreshSeriesInfo(seriesId).getOrThrow())

        val episodes = repo.observeEpisodes(seriesId).first()
        assertEquals(3, episodes.size)
        // Season then episode number — alphabetical would be actively wrong here.
        assertEquals(listOf(1, 1, 2), episodes.map { it.seasonNumber })
        assertEquals(listOf("533032", "533033", "533040"), episodes.map { it.episodeId })
        assertEquals(mapOf(1 to "Season One"), repo.lastSeasonNames)

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.contains("action=get_series_info"))
        assertTrue(path, path.contains("series_id=$seriesId"))
    }

    @Test
    fun a_second_open_inside_the_TTL_does_not_hit_the_panel() = runTest {
        server.enqueue(MockResponse().setBody(infoJson))
        repo.refreshSeriesInfo(seriesId).getOrThrow()

        // No second response is enqueued: a fetch here would hang the test rather than
        // pass quietly.
        assertEquals(false, repo.refreshSeriesInfo(seriesId).getOrThrow())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun a_manual_refresh_ignores_the_TTL() = runTest {
        server.enqueue(MockResponse().setBody(infoJson))
        repo.refreshSeriesInfo(seriesId).getOrThrow()

        server.enqueue(MockResponse().setBody(infoJson))
        assertTrue(repo.refreshSeriesInfo(seriesId, force = true).getOrThrow())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun a_refreshed_show_drops_episodes_the_panel_no_longer_lists() = runTest {
        server.enqueue(MockResponse().setBody(infoJson))
        repo.refreshSeriesInfo(seriesId).getOrThrow()

        server.enqueue(
            MockResponse().setBody(
                """{"episodes":{"1":[{"id":"533032","episode_num":1,"title":"Ep one"}]}}"""
            )
        )
        repo.refreshSeriesInfo(seriesId, force = true).getOrThrow()

        assertEquals(listOf("533032"), repo.observeEpisodes(seriesId).first().map { it.episodeId })
    }

    @Test
    fun a_rejected_series_info_call_leaves_the_cached_episodes_playable() = runTest {
        server.enqueue(MockResponse().setBody(infoJson))
        repo.refreshSeriesInfo(seriesId).getOrThrow()

        // The error-object answer parses to zero episodes; wiping the cached season on
        // that would make an already-downloaded show unplayable offline.
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))
        repo.refreshSeriesInfo(seriesId, force = true).getOrThrow()

        assertEquals(3, repo.observeEpisodes(seriesId).first().size)
    }

    @Test
    fun an_http_error_surfaces_as_a_failure_and_keeps_the_cache() = runTest {
        server.enqueue(MockResponse().setBody(infoJson))
        repo.refreshSeriesInfo(seriesId).getOrThrow()

        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(repo.refreshSeriesInfo(seriesId, force = true).isFailure)
        assertEquals(3, repo.observeEpisodes(seriesId).first().size)
    }

    @Test
    fun episode_freshness_cannot_collide_with_a_category_of_the_same_number() = runTest {
        // Both are stamped in `sync_meta` keyed by a string id. Under one content type,
        // show 770 and category 770 would share a stamp and each would make the other
        // look fresh.
        server.enqueue(MockResponse().setBody("""[{"series_id":1,"name":"A Show"}]"""))
        repo.refreshCategory("770").getOrThrow()

        server.enqueue(MockResponse().setBody(infoJson))
        assertTrue(repo.refreshSeriesInfo(770).getOrThrow())

        val catalogStamp = db.syncMetaDao().get(account, TYPE_SERIES, "770")
        val infoStamp = db.syncMetaDao().get(account, SeriesRepository.TYPE_SERIES_INFO, "770")
        assertTrue(catalogStamp != null && infoStamp != null)
    }
}
