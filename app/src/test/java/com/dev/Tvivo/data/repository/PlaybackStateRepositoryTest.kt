package com.dev.Tvivo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.TYPE_VOD
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
        repository.savePosition(TYPE_VOD, "42", 1_656_000, 1_800_000)

        assertNull(repository.resumePosition(TYPE_VOD, "42"))
    }
}