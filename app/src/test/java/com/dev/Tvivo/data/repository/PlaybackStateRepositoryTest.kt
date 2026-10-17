package com.dev.Tvivo.data.repository

import app.cash.turbine.test
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import com.dev.Tvivo.home.VirtualFolderKey
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackStateRepositoryTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun position_survives_a_new_repository_instance() = runTest {
        PlaybackStateRepository(db, "account-a")
            .savePosition(TYPE_VOD, "42", positionMs = 180_000, durationMs = 1_800_000)

        val restored = PlaybackStateRepository(db, "account-a")
            .resumePosition(TYPE_VOD, "42")

        assertNotNull(restored)
        assertEquals(180_000L, restored?.positionMs)
        assertEquals(1_800_000L, restored?.durationMs)
    }

    @Test
    fun movie_stopped_at_39_seconds_can_resume_and_appears_on_home() = runTest {
        val account = "account-a"
        db.vodDao().insertAll(listOf(VodStreamEntity(
            accountId = account, streamId = 2001, categoryId = "201",
            name = "Synthetic Film", nameDisplay = "Synthetic Film",
            nameNormalized = "synthetic film", streamIcon = null,
            containerExtension = "mp4", plot = null, rating = null,
            added = null, num = null, generation = 1
        )))
        val playback = PlaybackStateRepository(db, account)
        playback.savePosition(TYPE_VOD, "2001", 39_000, 960_000)

        assertEquals(39_000L, playback.resumePosition(TYPE_VOD, "2001")?.positionMs)
        val continueShelf = HomeRepository(db, account).load(seed = 1).shelves
            .first { it.destination == VirtualFolderKey.ContinueWatching }
        assertEquals(listOf(2001), continueShelf.cards.map { it.item.id })
        assertEquals("2001", continueShelf.cards.single().resumeItemId)
    }

    @Test
    fun saved_position_invalidates_detail_and_continue_watching_observers() = runTest {
        val playback = PlaybackStateRepository(db, "account-a")
        db.resumeDao().observe("account-a", TYPE_VOD, "2001").test {
            assertNull(awaitItem())
            playback.continueWatching(TYPE_VOD).test {
                assertEquals(emptyList<Any>(), awaitItem())
                playback.savePosition(TYPE_VOD, "2001", 49_237, 960_000)
                assertEquals(49_237L, awaitItem().single().positionMs)
            }
            assertEquals(49_237L, awaitItem()?.positionMs)
        }
    }

    @Test
    fun movie_without_known_duration_still_appears_in_continue_watching() = runTest {
        val playback = PlaybackStateRepository(db, "account-a")
        playback.savePosition(TYPE_VOD, "2001", 39_000, 0)

        assertEquals(39_000L, playback.resumePosition(TYPE_VOD, "2001")?.positionMs)
        assertEquals(1, db.resumeDao().continueSnapshot("account-a", TYPE_VOD).size)
    }

    @Test
    fun a_short_new_visit_does_not_erase_an_existing_resume_point() = runTest {
        val repository = PlaybackStateRepository(db, "account-a")
        repository.savePosition(TYPE_VOD, "42", 180_000, 1_800_000)
        repository.savePosition(TYPE_VOD, "42", 10_000, 1_800_000)

        assertEquals(180_000L, repository.resumePosition(TYPE_VOD, "42")?.positionMs)
    }

    @Test
    fun a_completed_item_is_removed_from_resume_storage() = runTest {
        val repository = PlaybackStateRepository(db, "account-a")
        repository.savePosition(TYPE_VOD, "42", 180_000, 1_800_000)
        repository.savePosition(TYPE_VOD, "42", 1_720_000, 1_800_000)

        assertNull(repository.resumePosition(TYPE_VOD, "42"))
        assertNotNull(db.watchedDao().get("account-a", "account-a", TYPE_VOD, "42"))
    }

    @Test
    fun episode_uses_fixed_tail_and_persists_watched_state() = runTest {
        val repository = PlaybackStateRepository(db, "account-a")
        repository.savePosition(TYPE_SERIES, "ep", 1_700_000, 1_800_000)
        assertEquals(1_700_000L, repository.resumePosition(TYPE_SERIES, "ep")?.positionMs)
        assertNull(db.watchedDao().get("account-a", "account-a", TYPE_SERIES, "ep"))

        repository.savePosition(TYPE_SERIES, "ep", 1_788_000, 1_800_000)
        assertNull(repository.resumePosition(TYPE_SERIES, "ep"))
        assertNotNull(db.watchedDao().get("account-a", "account-a", TYPE_SERIES, "ep"))

        repository.savePosition(TYPE_SERIES, "ep", 300_000, 1_800_000)
        assertEquals(300_000L, repository.resumePosition(TYPE_SERIES, "ep")?.positionMs)
        assertNull(db.watchedDao().get("account-a", "account-a", TYPE_SERIES, "ep"))
    }

    @Test
    fun genuineEndMarksAShortEpisodeWatched() = runTest {
        val repository = PlaybackStateRepository(db, "account-a")
        repository.savePosition(TYPE_SERIES, "short", 50_000, 50_000, ended = true)
        assertNotNull(db.watchedDao().get("account-a", "account-a", TYPE_SERIES, "short"))
        assertNull(repository.resumePosition(TYPE_SERIES, "short"))
    }

    @Test
    fun timedStopNearTheTailKeepsResumeWithoutMarkingWatched() = runTest {
        val repository = PlaybackStateRepository(db, "account-a")
        repository.savePosition(TYPE_VOD, "timer-movie", 1_790_000, 1_800_000,
            allowCompletion = false)

        assertEquals(1_790_000L, repository.resumePosition(TYPE_VOD, "timer-movie")?.positionMs)
        assertNull(db.watchedDao().get("account-a", "account-a", TYPE_VOD, "timer-movie"))
    }

    @Test
    fun timedStopSavesShortPositiveProgress() = runTest {
        val repository = PlaybackStateRepository(db, "account-a")
        repository.savePosition(TYPE_SERIES, "timer-episode", 30_000, 1_800_000,
            allowCompletion = false)

        assertEquals(30_000L,
            repository.resumePosition(TYPE_SERIES, "timer-episode")?.positionMs)
        assertNull(db.watchedDao().get("account-a", "account-a", TYPE_SERIES, "timer-episode"))
    }
}
