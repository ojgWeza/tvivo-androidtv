package com.dev.Tvivo.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.data.local.entities.MediaFileSizeEntity
import com.dev.Tvivo.data.local.entities.DownloadEntity
import com.dev.Tvivo.data.repository.MediaFileSizeRepository
import com.dev.Tvivo.download.DownloadFileSystem
import com.dev.Tvivo.download.DownloadRepository
import com.dev.Tvivo.download.DownloadRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadRepositoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeFiles : DownloadFileSystem {
        val lengths = mutableMapOf<String, Long>()
        override fun length(relativePath: String) = lengths[relativePath]
        override fun delete(relativePath: String) = lengths.remove(relativePath) != null
    }

    @Test fun concurrentEnqueuesCannotOversubscribeAndReconcileUsesFileLengths() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val files = FakeFiles()
        try {
            val sizeDao = db.mediaFileSizeDao()
            listOf("one", "two").forEach { id ->
                sizeDao.upsertExact(MediaFileSizeEntity("acct", "movie", 0, id, exactBytes = 60))
            }
            val repo = DownloadRepository(db, db.downloadDao(), MediaFileSizeRepository(sizeDao), files, { 100 })
            val first = DownloadRequest("acct", "movie", 0, "one", "mp4", "a.part")
            val second = DownloadRequest("acct", "movie", 0, "two", "mp4", "b.part")
            val a = async { repo.enqueue(listOf(first)) }
            val b = async { repo.enqueue(listOf(second)) }
            val results = listOf(a.await(), b.await())
            assertEquals(1, results.count { it is DownloadRepository.EnqueueResult.Accepted })
            assertEquals(1, results.count { it is DownloadRepository.EnqueueResult.Rejected })

            val accepted = db.downloadDao().getAll().single()
            db.downloadDao().upsert(accepted.copy(state = "FAILED", partialBytes = 99, reservedBytes = 777))
            files.lengths[accepted.relativePath!!] = 43
            val reconciled = repo.reconcile().single()
            assertEquals(43L, reconciled.partialBytes)
            assertEquals(0L, reconciled.reservedBytes)

            db.downloadDao().upsert(reconciled.copy(state = "COMPLETED", relativePath = "done.mp4", partialBytes = 43))
            files.lengths.remove(accepted.relativePath)
            val missing = repo.reconcile().single()
            assertEquals("FAILED", missing.state)
            assertEquals("missing", missing.failureReason)
        } finally { db.close() }
    }

    @Test fun rejectedUnknownSeasonBatchLeavesNoRows() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.mediaFileSizeDao().upsertExact(MediaFileSizeEntity("acct", "episode", 9, "known", exactBytes = 20))
            val repo = DownloadRepository(db, db.downloadDao(), MediaFileSizeRepository(db.mediaFileSizeDao()), FakeFiles(), { 1_000 })
            val result = repo.enqueue(listOf(
                DownloadRequest("acct", "episode", 9, "known", "mp4", "series/9/known.mp4"),
                DownloadRequest("acct", "episode", 9, "unknown", "mp4", "series/9/unknown.mp4")
            ))
            assertTrue(result is DownloadRepository.EnqueueResult.Rejected)
            assertTrue(db.downloadDao().getAll().isEmpty())
        } finally { db.close() }
    }

}
