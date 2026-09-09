package com.dev.Tvivo.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import com.dev.Tvivo.data.local.entities.LiveStreamEntity
import com.dev.Tvivo.data.local.entities.SeriesEntity
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DAO and **transaction** behaviour — the paths where a silent bug loses the whole
 * cached catalog. The zero-row generation flip shipped behind 66 green tests precisely
 * because none of them touched Room.
 *
 * Robolectric rather than an instrumented test: this is SQLite behaviour, and it should
 * be caught by `./gradlew test` on every change, not only when an emulator is up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogDaoTest {

    private lateinit var db: AppDatabase

    private val account = "acct-a"
    private val other = "acct-b"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun vod(
        id: Int,
        categoryId: String?,
        name: String = "Film $id",
        generation: Long = 0,
        accountId: String = account
    ) = VodStreamEntity(
        accountId = accountId,
        streamId = id,
        categoryId = categoryId,
        name = name,
        nameDisplay = name,
        nameNormalized = name.lowercase(),
        streamIcon = null,
        containerExtension = "mp4",
        plot = null,
        added = null,
        num = null,
        generation = generation
    )

    private fun live(
        id: Int,
        categoryId: String?,
        name: String = "Channel $id",
        generation: Long = 0
    ) = LiveStreamEntity(
        accountId = account,
        streamId = id,
        categoryId = categoryId,
        name = name,
        nameDisplay = name,
        nameNormalized = name.lowercase(),
        streamIcon = null,
        ext = "ts",
        added = null,
        num = null,
        generation = generation
    )

    private suspend fun vodCount(categoryId: String, accountId: String = account): Int =
        db.vodDao().countsByCategory(accountId).first()
            .firstOrNull { it.categoryId == categoryId }?.count ?: 0

    // --- replaceCategory -----------------------------------------------------

    @Test
    fun replaceCategory_only_touches_the_category_it_names() = runTest {
        db.vodDao().insertAll(listOf(vod(1, "10"), vod(2, "10"), vod(3, "20")))

        db.vodDao().replaceCategory(account, "10", listOf(vod(4, "10")))

        assertEquals(1, vodCount("10"))
        assertEquals(1, vodCount("20"))
        assertNotNull(db.vodDao().byId(account, 3))
        assertNull(db.vodDao().byId(account, 1))
    }

    @Test
    fun replaceCategory_is_account_scoped() = runTest {
        db.vodDao().insertAll(listOf(vod(1, "10"), vod(1, "10", accountId = other)))

        db.vodDao().replaceCategory(account, "10", emptyList())

        assertEquals(0, vodCount("10"))
        assertEquals(1, vodCount("10", other))
    }

    @Test
    fun replaceCategory_writes_past_one_chunk() = runTest {
        // 500 is the chunk size; 1200 rows span three inserts inside one transaction.
        val rows = (1..1200).map { vod(it, "10") }

        db.vodDao().replaceCategory(account, "10", rows)

        assertEquals(1200, vodCount("10"))
    }

    @Test
    fun replaceCategory_with_an_empty_list_empties_the_category() = runTest {
        db.vodDao().insertAll(listOf(vod(1, "10")))

        db.vodDao().replaceCategory(account, "10", emptyList())

        assertEquals(0, vodCount("10"))
    }

    // --- generation flip -----------------------------------------------------

    @Test
    fun deleteGenerationsUpTo_keeps_the_newest_generation_and_drops_the_rest() = runTest {
        db.vodDao().insertAll(
            listOf(vod(1, "10", generation = 100), vod(2, "20", generation = 100))
        )
        db.vodDao().insertAll(listOf(vod(3, "10", generation = 200)))

        db.vodDao().deleteGenerationsUpTo(account, 199)

        assertNull(db.vodDao().byId(account, 1))
        assertNull(db.vodDao().byId(account, 2))
        assertNotNull(db.vodDao().byId(account, 3))
    }

    @Test
    fun a_resynced_row_replaces_the_old_row_rather_than_duplicating_it() = runTest {
        db.vodDao().insertAll(listOf(vod(1, "10", name = "Old", generation = 100)))
        db.vodDao().insertAll(listOf(vod(1, "10", name = "New", generation = 200)))

        db.vodDao().deleteGenerationsUpTo(account, 199)

        // The key is (accountId, streamId), so the second insert overwrote the first and
        // carries the new generation — deleting the old generation must not take it.
        assertEquals("New", db.vodDao().byId(account, 1)?.name)
        assertEquals(1, vodCount("10"))
    }

    @Test
    fun deleteGenerationsUpTo_is_account_scoped() = runTest {
        db.vodDao().insertAll(
            listOf(
                vod(1, "10", generation = 100),
                vod(1, "10", generation = 100, accountId = other)
            )
        )

        db.vodDao().deleteGenerationsUpTo(account, 100)

        assertNull(db.vodDao().byId(account, 1))
        assertNotNull(db.vodDao().byId(other, 1))
    }

    // --- separate tables per content type ------------------------------------

    @Test
    fun a_channel_and_a_film_may_share_a_stream_id_without_overwriting_each_other() = runTest {
        // The reason live is a separate table rather than a `type` column: `stream_id` is
        // unique only *within* a content type on this panel.
        db.vodDao().insertAll(listOf(vod(555, "10", name = "A Film")))
        db.liveDao().insertAll(listOf(live(555, "30", name = "A Channel")))

        assertEquals("A Film", db.vodDao().byId(account, 555)?.name)
        assertEquals("A Channel", db.liveDao().byId(account, 555)?.name)
    }

    @Test
    fun live_replaceCategory_and_generation_flip_behave_as_vod_does() = runTest {
        db.liveDao().insertAll(
            listOf(live(1, "30", generation = 100), live(2, "40", generation = 100))
        )
        db.liveDao().replaceCategory(account, "30", listOf(live(3, "30", generation = 200)))

        assertNull(db.liveDao().byId(account, 1))
        assertNotNull(db.liveDao().byId(account, 2))

        db.liveDao().deleteGenerationsUpTo(account, 199)
        assertNull(db.liveDao().byId(account, 2))
        assertNotNull(db.liveDao().byId(account, 3))
    }

    // --- series and episodes -------------------------------------------------

    private fun series(id: Int, categoryId: String?, name: String = "Show $id") = SeriesEntity(
        accountId = account,
        seriesId = id,
        categoryId = categoryId,
        name = name,
        nameDisplay = name,
        nameNormalized = name.lowercase(),
        streamIcon = null,
        plot = null,
        added = null,
        num = null,
        generation = 0
    )

    private fun episode(seriesId: Int, episodeId: String, season: Int, number: Int) =
        EpisodeEntity(
            accountId = account,
            seriesId = seriesId,
            episodeId = episodeId,
            seasonNumber = season,
            episodeNum = number,
            title = "S%02dE%02d".format(season, number),
            containerExtension = "mkv",
            durationSecs = null,
            added = null
        )

    @Test
    fun a_show_a_channel_and_a_film_may_all_share_an_id() = runTest {
        db.vodDao().insertAll(listOf(vod(777, "10", name = "A Film")))
        db.liveDao().insertAll(listOf(live(777, "30", name = "A Channel")))
        db.seriesDao().insertAll(listOf(series(777, "770", name = "A Show")))

        assertEquals("A Film", db.vodDao().byId(account, 777)?.name)
        assertEquals("A Channel", db.liveDao().byId(account, 777)?.name)
        assertEquals("A Show", db.seriesDao().byId(account, 777)?.name)
    }

    @Test
    fun episodes_come_back_ordered_by_season_then_episode_number() = runTest {
        // The picker renders this order directly, and unlike the catalog lists,
        // alphabetical would be actively wrong.
        db.seriesDao().insertEpisodes(
            listOf(
                episode(1, "c", season = 2, number = 1),
                episode(1, "b", season = 1, number = 10),
                episode(1, "a", season = 1, number = 2)
            )
        )

        assertEquals(
            listOf("a", "b", "c"),
            db.seriesDao().observeEpisodes(account, 1).first().map { it.episodeId }
        )
    }

    @Test
    fun replaceEpisodes_drops_episodes_the_panel_no_longer_lists() = runTest {
        db.seriesDao().insertEpisodes(
            listOf(episode(1, "a", 1, 1), episode(1, "b", 1, 2))
        )

        db.seriesDao().replaceEpisodes(account, 1, listOf(episode(1, "a", 1, 1)))

        assertEquals(
            listOf("a"),
            db.seriesDao().observeEpisodes(account, 1).first().map { it.episodeId }
        )
    }

    @Test
    fun replaceEpisodes_refuses_to_wipe_a_cached_season_on_an_empty_result() = runTest {
        // `get_series_info` answering with an error object parses to zero episodes.
        // Wiping on that makes an already-cached show unplayable offline.
        db.seriesDao().insertEpisodes(listOf(episode(1, "a", 1, 1)))

        db.seriesDao().replaceEpisodes(account, 1, emptyList())

        assertEquals(1, db.seriesDao().observeEpisodes(account, 1).first().size)
    }

    @Test
    fun episodes_of_one_show_are_untouched_by_another_show_refresh() = runTest {
        db.seriesDao().insertEpisodes(listOf(episode(1, "a", 1, 1), episode(2, "b", 1, 1)))

        db.seriesDao().replaceEpisodes(account, 1, listOf(episode(1, "c", 1, 1)))

        assertEquals(listOf("c"), db.seriesDao().observeEpisodes(account, 1).first().map { it.episodeId })
        assertEquals(listOf("b"), db.seriesDao().observeEpisodes(account, 2).first().map { it.episodeId })
    }

    // --- categories ----------------------------------------------------------

    @Test
    fun replaceAll_drops_categories_the_panel_no_longer_lists() = runTest {
        val dao = db.categoryDao()
        dao.replaceAll(
            account, TYPE_VOD,
            listOf(
                CategoryEntity(account, TYPE_VOD, "10", "Action", 0),
                CategoryEntity(account, TYPE_VOD, "20", "Comedy", 1)
            )
        )

        dao.replaceAll(
            account, TYPE_VOD,
            listOf(CategoryEntity(account, TYPE_VOD, "10", "Action & Adventure", 0))
        )

        val rows = dao.observe(account, TYPE_VOD).first()
        assertEquals(listOf("10"), rows.map { it.categoryId })
        assertEquals("Action & Adventure", rows.single().name)
    }

    @Test
    fun categories_are_ordered_by_the_panel_ordering_not_insertion_order() = runTest {
        db.categoryDao().replaceAll(
            account, TYPE_VOD,
            listOf(
                CategoryEntity(account, TYPE_VOD, "30", "Third", 2),
                CategoryEntity(account, TYPE_VOD, "10", "First", 0),
                CategoryEntity(account, TYPE_VOD, "20", "Second", 1)
            )
        )

        assertEquals(
            listOf("First", "Second", "Third"),
            db.categoryDao().observe(account, TYPE_VOD).first().map { it.name }
        )
    }
}
