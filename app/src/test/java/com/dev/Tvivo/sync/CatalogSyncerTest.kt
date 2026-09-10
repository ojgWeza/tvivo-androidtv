package com.dev.Tvivo.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.LiveStreamEntity
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.local.entities.SeriesEntity
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The full-catalog tier end to end: MockWebServer for the panel, in-memory Room for the
 * cache. The bug this exists for is specific — a panel that rejects the no-`category_id`
 * call answers with an **object**, the parser reports zero rows, and flipping the
 * generation on that deletes every per-category row the user already had.
 *
 * That shipped in the movies path behind 66 green tests and was found only by building
 * live. Series makes it a third caller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogSyncerTest {

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var syncer: CatalogSyncer

    private val account = "acct-a"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        val credentials = Credentials(
            host = server.hostName,
            port = server.port,
            username = "u",
            password = "p"
        )
        syncer = CatalogSyncer(db, credentials, account)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun existingVodRow(streamId: Int) = VodStreamEntity(
        accountId = account,
        streamId = streamId,
        categoryId = "10",
        name = "Cached film",
        nameDisplay = "Cached film",
        nameNormalized = "cached film",
        streamIcon = null,
        containerExtension = "mp4",
        plot = null,
        rating = null,
        added = null,
        num = null,
        // Written by the per-category path, which always uses generation 0.
        generation = 0
    )

    private fun existingLiveRow(streamId: Int) = LiveStreamEntity(
        accountId = account,
        streamId = streamId,
        categoryId = "30",
        name = "Cached channel",
        nameDisplay = "Cached channel",
        nameNormalized = "cached channel",
        streamIcon = null,
        ext = "ts",
        added = null,
        num = null,
        generation = 0
    )

    private fun existingSeriesRow(seriesId: Int) = SeriesEntity(
        accountId = account,
        seriesId = seriesId,
        categoryId = "770",
        name = "Cached show",
        nameDisplay = "Cached show",
        nameNormalized = "cached show",
        streamIcon = null,
        plot = null,
        rating = null,
        added = null,
        num = null,
        generation = 0
    )

    private fun vodJson(vararg ids: Int) = ids.joinToString(",", "[", "]") { id ->
        """{"stream_id":$id,"name":"Film $id","category_id":"10","container_extension":"mp4"}"""
    }

    private suspend fun state(contentType: String) =
        db.catalogSyncDao().get(account, contentType)?.state

    @Test
    fun a_full_catalog_response_replaces_the_previous_generation() = runTest {
        db.vodDao().insertAll(listOf(existingVodRow(1)))
        server.enqueue(MockResponse().setBody(vodJson(2, 3)))

        val written = syncer.syncVod().getOrThrow()

        assertEquals(2, written)
        assertEquals(CatalogSyncer.STATE_COMPLETE, state(TYPE_VOD))
        // The old generation-0 row is gone, the new rows are in.
        assertNull(db.vodDao().byId(account, 1))
        assertNotNull(db.vodDao().byId(account, 2))
        assertNotNull(db.vodDao().byId(account, 3))
    }

    @Test
    fun a_panel_that_rejects_the_call_must_not_delete_the_cached_catalog() = runTest {
        db.vodDao().insertAll(listOf(existingVodRow(1)))
        // The shape a rejecting panel actually returns: an object, not an array.
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))

        val written = syncer.syncVod().getOrThrow()

        assertEquals(0, written)
        assertEquals(CatalogSyncer.STATE_PARTIAL, state(TYPE_VOD))
        assertNotNull("the cached catalog must survive a zero-row answer", db.vodDao().byId(account, 1))
    }

    @Test
    fun a_genuinely_empty_array_is_also_treated_as_partial() = runTest {
        // Indistinguishable from the rejection above at this layer, and the safe reading
        // of both is the same: keep what is cached.
        db.vodDao().insertAll(listOf(existingVodRow(1)))
        server.enqueue(MockResponse().setBody("[]"))

        assertEquals(0, syncer.syncVod().getOrThrow())
        assertEquals(CatalogSyncer.STATE_PARTIAL, state(TYPE_VOD))
        assertNotNull(db.vodDao().byId(account, 1))
    }

    @Test
    fun an_http_error_leaves_the_previous_generation_intact_and_records_failure() = runTest {
        db.vodDao().insertAll(listOf(existingVodRow(1)))
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(syncer.syncVod().isFailure)
        assertEquals(CatalogSyncer.STATE_FAILED, state(TYPE_VOD))
        assertNotNull(db.vodDao().byId(account, 1))
    }

    @Test
    fun a_response_that_truncates_mid_array_leaves_the_catalog_browsable() = runTest {
        db.vodDao().insertAll(listOf(existingVodRow(1)))
        server.enqueue(
            MockResponse().setBody("""[{"stream_id":2,"name":"Half a film"""")
        )

        assertTrue(syncer.syncVod().isFailure)
        assertEquals(CatalogSyncer.STATE_FAILED, state(TYPE_VOD))
        // Nothing was flipped, so the pre-existing rows are still there to browse.
        assertNotNull(db.vodDao().byId(account, 1))
    }

    @Test
    fun the_sync_writes_past_the_parser_chunk_boundary() = runTest {
        // 500 is the chunk size; 1200 exercises the multi-chunk write and the progress
        // counter that drives the header.
        server.enqueue(MockResponse().setBody(vodJson(*(1..1200).toList().toIntArray())))

        assertEquals(1200, syncer.syncVod().getOrThrow())
        assertEquals(1200, db.catalogSyncDao().get(account, TYPE_VOD)?.done)
    }

    @Test
    fun live_carries_the_same_zero_row_guard_as_vod() = runTest {
        db.liveDao().insertAll(listOf(existingLiveRow(1)))
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))

        assertEquals(0, syncer.syncLive().getOrThrow())
        assertEquals(CatalogSyncer.STATE_PARTIAL, state(TYPE_LIVE))
        assertNotNull(db.liveDao().byId(account, 1))
    }

    @Test
    fun series_carries_the_same_zero_row_guard_as_vod() = runTest {
        db.seriesDao().insertAll(listOf(existingSeriesRow(1)))
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))

        assertEquals(0, syncer.syncSeries().getOrThrow())
        assertEquals(CatalogSyncer.STATE_PARTIAL, state(TYPE_SERIES))
        assertNotNull(db.seriesDao().byId(account, 1))
    }

    @Test
    fun a_series_catalog_response_replaces_the_previous_generation() = runTest {
        db.seriesDao().insertAll(listOf(existingSeriesRow(1)))
        server.enqueue(
            MockResponse().setBody("""[{"series_id":2,"name":"A Show","category_id":770}]""")
        )

        assertEquals(1, syncer.syncSeries().getOrThrow())
        assertEquals(CatalogSyncer.STATE_COMPLETE, state(TYPE_SERIES))
        assertNull(db.seriesDao().byId(account, 1))
        assertNotNull(db.seriesDao().byId(account, 2))
    }

    @Test
    fun a_show_and_a_film_sharing_an_id_survive_each_other_s_syncs() = runTest {
        // Separate tables, separate generations: syncing one content type must not touch
        // another type's row that happens to carry the same numeric id.
        server.enqueue(MockResponse().setBody(vodJson(555)))
        syncer.syncVod().getOrThrow()

        server.enqueue(MockResponse().setBody("""[{"series_id":555,"name":"A Show"}]"""))
        syncer.syncSeries().getOrThrow()

        assertNotNull(db.vodDao().byId(account, 555))
        assertNotNull(db.seriesDao().byId(account, 555))
    }

    @Test
    fun vod_and_live_progress_are_tracked_separately() = runTest {
        server.enqueue(MockResponse().setBody(vodJson(1)))
        syncer.syncVod().getOrThrow()

        assertEquals(CatalogSyncer.STATE_COMPLETE, state(TYPE_VOD))
        // Live has not run, so it must have no state of its own rather than inheriting VOD's.
        assertNull(state(TYPE_LIVE))
    }

    // ---- TTL gate -------------------------------------------------------------
    //
    // BrowseViewModel constructs a syncer and calls sync* in `init`, so every entry into
    // a listing hit this path. With no freshness check that re-downloaded the whole
    // catalog each time: verified on-emulator at 13,264 series rows rewritten 11 minutes
    // after a `complete` run, with the progress line reading "Indexing" over data that
    // was already there.

    @Test
    fun a_second_sync_inside_the_ttl_does_not_refetch() = runTest {
        server.enqueue(MockResponse().setBody(vodJson(1, 2)))
        assertEquals(2, syncer.syncVod().getOrThrow())
        assertEquals(1, server.requestCount)

        // No second response is enqueued: if this call reached the network at all, it
        // would block or fail rather than return 0.
        assertEquals(0, syncer.syncVod().getOrThrow())
        assertEquals(1, server.requestCount)
        assertEquals(CatalogSyncer.STATE_COMPLETE, state(TYPE_VOD))
        // The cached rows are untouched.
        assertNotNull(db.vodDao().byId(account, 1))
        assertNotNull(db.vodDao().byId(account, 2))
    }

    @Test
    fun force_ignores_the_ttl() = runTest {
        server.enqueue(MockResponse().setBody(vodJson(1)))
        syncer.syncVod().getOrThrow()
        assertEquals(1, server.requestCount)

        // `Refresh all` on Home is the caller that must still fetch.
        server.enqueue(MockResponse().setBody(vodJson(9)))
        assertEquals(1, syncer.syncVod(force = true).getOrThrow())
        assertEquals(2, server.requestCount)
        assertNotNull(db.vodDao().byId(account, 9))
        assertNull(db.vodDao().byId(account, 1))
    }

    @Test
    fun the_ttl_expiring_lets_the_next_sync_through() = runTest {
        server.enqueue(MockResponse().setBody(vodJson(1)))
        syncer.syncVod().getOrThrow()

        // Age the completed run past the window.
        val row = db.catalogSyncDao().get(account, TYPE_VOD)!!
        db.catalogSyncDao().upsert(
            row.copy(updatedAt = row.updatedAt - CatalogSyncer.TTL_MILLIS - 1)
        )

        server.enqueue(MockResponse().setBody(vodJson(7)))
        assertEquals(1, syncer.syncVod().getOrThrow())
        assertEquals(2, server.requestCount)
        assertNotNull(db.vodDao().byId(account, 7))
    }

    @Test
    fun a_partial_run_is_not_treated_as_fresh() = runTest {
        db.vodDao().insertAll(listOf(existingVodRow(1)))
        // A rejecting panel records `partial`, never `complete`.
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))
        assertEquals(0, syncer.syncVod().getOrThrow())
        assertEquals(CatalogSyncer.STATE_PARTIAL, state(TYPE_VOD))

        // The catalog is not known to be whole, so the next entry must retry rather than
        // sit on a partial cache for 24 h.
        server.enqueue(MockResponse().setBody(vodJson(4, 5)))
        assertEquals(2, syncer.syncVod().getOrThrow())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun a_failed_run_is_not_treated_as_fresh() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(syncer.syncVod().isFailure)
        assertEquals(CatalogSyncer.STATE_FAILED, state(TYPE_VOD))

        server.enqueue(MockResponse().setBody(vodJson(3)))
        assertEquals(1, syncer.syncVod().getOrThrow())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun the_ttl_is_tracked_per_content_type() = runTest {
        server.enqueue(MockResponse().setBody(vodJson(1)))
        syncer.syncVod().getOrThrow()

        // A fresh VOD catalog must not make series look fresh: they are separate rows in
        // `catalog_sync`, and conflating them would leave series permanently unsynced.
        server.enqueue(MockResponse().setBody("""[{"series_id":8,"name":"A Show"}]"""))
        assertEquals(1, syncer.syncSeries().getOrThrow())
        assertEquals(2, server.requestCount)
        assertNotNull(db.seriesDao().byId(account, 8))
    }
}
