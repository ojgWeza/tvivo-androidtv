package com.dev.Tvivo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import com.dev.Tvivo.source.SourceId
import com.dev.Tvivo.source.library.ParseResult
import com.dev.Tvivo.source.library.parseMovies
import com.dev.Tvivo.source.library.parseMusic
import com.dev.Tvivo.source.library.parseSeries
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveRepositoryTest {
    private lateinit var db: AppDatabase
    private val id = "11111111-1111-4111-8111-111111111111"
    private val episodeId = "22222222-2222-4222-8222-222222222222"
    private val account = SourceId.drive("permission-a")
    private val other = SourceId.drive("permission-b")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun movies(title: String = "First", items: Boolean = true): String =
        """{"format":"tvivo.link-library","version":1,"kind":"movies","items":${if (items) """[{"id":"$id","title":"$title","group":"Classics","streamUrl":"https://example.org/movie.mp4"}]""" else "[]"}}"""

    @Test fun generationFlipAndRejectedParseKeepLastGood() = runTest {
        val repo = DriveRepository(db, account)
        assertTrue(repo.applyMovies(parseMovies(movies()), 10))
        assertEquals(1L, db.driveDao().state(account.value, "movies")?.generation)
        assertFalse(repo.applyMovies(parseMovies("{"), 20))
        assertEquals("First", repo.movie(id)?.title)
        assertEquals(10L, db.driveDao().state(account.value, "movies")?.lastSuccessfulSyncAt)
        assertTrue(repo.applyMovies(parseMovies(movies("Renamed")), 30))
        assertEquals("Renamed", repo.movie(id)?.title)
        assertEquals(2L, db.driveDao().state(account.value, "movies")?.generation)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM drive_movies").use {
            it.moveToFirst(); assertEquals(1, it.getInt(0))
        }
        assertEquals(id, repo.searchMovies("RENAMED").single().id)
    }

    @Test fun emptyMovieListClearsOnlyMoviesAndDriveNeverTouchesXtream() = runTest {
        val repo = DriveRepository(db, account)
        val music = """{"format":"tvivo.link-library","version":1,"kind":"music","items":[{"id":"$id","title":"Song","group":"Music","streamUrl":"https://example.org/song.mp3"}]}"""
        assertTrue(repo.applyMovies(parseMovies(movies()), 1))
        assertTrue(repo.applyMusic(parseMusic(music), 1))
        db.vodDao().insertAll(listOf(VodStreamEntity(accountId = "legacy", streamId = 7,
            categoryId = "old", name = "Panel", nameDisplay = "Panel", nameNormalized = "panel",
            streamIcon = null, containerExtension = "mp4", plot = null, rating = null,
            added = null, num = null, generation = 0)))
        assertTrue(repo.applyMovies(parseMovies(movies(items = false)), 2))
        assertNull(repo.movie(id))
        assertEquals(id, repo.track(id)?.id)
        assertEquals(1, db.vodDao().byIds("legacy", listOf(7)).size)
        assertTrue(repo.categories(TYPE_VOD).first().isEmpty())
        assertNull(DriveRepository(db, other).track(id))
    }

    @Test fun seriesHierarchyUsesStableStringIds() = runTest {
        val repo = DriveRepository(db, account)
        val series = """{"format":"tvivo.link-library","version":1,"kind":"series","items":[{"id":"$id","title":"Show","group":"Classics","seasons":[{"number":1,"title":"First season","episodes":[{"id":"$episodeId","number":1,"title":"Pilot","streamUrl":"https://example.org/pilot.mp4"}]}]}]}"""
        assertTrue(repo.applySeries(parseSeries(series), 1))
        assertEquals(id, repo.show(id)?.id)
        assertEquals("First season", repo.seasons(id).first().single().title)
        assertEquals(episodeId, repo.episodes(id).first().single().id)
        assertEquals(episodeId, repo.episode(episodeId)?.id)
        assertEquals(id, repo.searchShows("SHOW").single().id)
        assertFalse(repo.applySeries(ParseResult.Rejected(com.dev.Tvivo.source.library.RejectionReason.MALFORMED_JSON, "bad"), 2))
        assertEquals(episodeId, repo.episode(episodeId)?.id)
    }
}
