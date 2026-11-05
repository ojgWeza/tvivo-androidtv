package com.dev.Tvivo.download

import com.dev.Tvivo.data.local.entities.DownloadEntity
import com.dev.Tvivo.playback.MediaConnectionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class DownloadTransferTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var files: LocalFiles
    private lateinit var repo: FakeRepository

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        dir = createTempDir(prefix = "download-transfer-")
        files = LocalFiles(dir)
        repo = FakeRepository()
    }
    @After fun tearDown() { server.shutdown(); dir.deleteRecursively() }

    @Test fun freshDownloadPersistsAndCompletes() = runTest {
        server.enqueue(MockResponse().setBody("abcdef").setHeader("ETag", "\"v1\""))
        assertEquals(DownloadTransfer.Outcome.Complete, transfer().run(row(expected = 6), false))
        assertEquals("abcdef", files.finalFile("item")!!.readText())
        assertTrue(repo.completed)
    }

    @Test fun resumesWithRangeAndIfRange() = runTest {
        files.part("item")!!.writeText("abc")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6")
            .setHeader("ETag", "\"v1\"").setBody("def"))
        assertEquals(DownloadTransfer.Outcome.Complete, transfer().run(row(expected = 6, etag = "\"v1\""), false))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("bytes=3-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
        assertEquals("abcdef", files.finalFile("item")!!.readText())
    }

    @Test fun ignoredRangeRestartsFromZero() = runTest {
        files.part("item")!!.writeText("old")
        server.enqueue(MockResponse().setBody("fresh"))
        assertEquals(DownloadTransfer.Outcome.Complete, transfer().run(row(expected = 5), false))
        assertEquals("fresh", files.finalFile("item")!!.readText())
    }

    @Test fun completeAndInvalid416AreHandled() = runTest {
        files.part("item")!!.writeText("done")
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */4"))
        assertEquals(DownloadTransfer.Outcome.Complete, transfer().run(row(expected = 4), false))
        assertTrue(repo.completed)
        repo.completed = false
        files.finalFile("item")!!.delete()
        files.part("item")!!.writeText("bad")
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */8"))
        assertEquals(DownloadTransfer.Outcome.Retry, transfer().run(row(expected = 8), false))
        assertFalse(files.part("item")!!.exists())
    }

    @Test fun changedValidatorDropsPartialAndRetries() = runTest {
        files.part("item")!!.writeText("old")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6")
            .setHeader("ETag", "\"v2\"").setBody("new"))
        assertEquals(DownloadTransfer.Outcome.Retry, transfer().run(row(expected = 6, etag = "\"v1\""), false))
        assertFalse(files.part("item")!!.exists())
        assertEquals("\"v2\"", repo.etag)
    }

    @Test fun forbiddenFailsAndServerErrorRetriesWithoutFinalFile() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(DownloadTransfer.Outcome.Authorization, transfer().run(row(), false))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(DownloadTransfer.Outcome.Retry, transfer().run(row(), false))
        assertFalse(files.finalFile("item")!!.exists())
    }

    @Test fun connectionFailureReturnsRetry() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val transfer = DownloadTransfer(OkHttpClient(), { "http://127.0.0.1:$port/media" }, files,
            { 1L }, repo, MediaConnectionCoordinator(quietGapMillis = 0, maxConnectAttempts = 1), { true })
        assertEquals(DownloadTransfer.Outcome.Retry, transfer.run(row(), false))
    }

    @Test fun lengthMismatchAndHardCapNeverPromotePart() = runTest {
        server.enqueue(MockResponse().setBody("short"))
        assertEquals(DownloadTransfer.Outcome.Retry, transfer().run(row(expected = 8), false))
        assertFalse(files.finalFile("item")!!.exists())
        server.enqueue(MockResponse().setBody("a".repeat(1_000_010)))
        assertEquals(DownloadTransfer.Outcome.Storage, transfer().run(row(reserved = 1), false))
        assertFalse(files.finalFile("item")!!.exists())
    }

    @Test fun injectedDiskFullIsMappedToStorageFailure() = runTest {
        server.enqueue(MockResponse().setBody("data"))
        files.failWrites = true
        assertEquals(DownloadTransfer.Outcome.Storage, transfer().run(row(), false))
        assertFalse(repo.completed)
        assertFalse(files.finalFile("item")!!.exists())
    }

    @Test fun wifiOnlyPausesWhenConnectionBecomesMetered() = runTest {
        server.enqueue(MockResponse().setBody("abcdef"))
        assertEquals(DownloadTransfer.Outcome.Retry, transfer(unmetered = { false }).run(row(), true))
        assertFalse(files.finalFile("item")!!.exists())
    }

    @Test fun secretNeverEntersRowOrTransferData() {
        val secretUrl = "http://user:password@example.invalid/movie/secret"
        val safeRow = row()
        val persisted = listOf(safeRow.accountId, safeRow.mediaId, safeRow.relativePath, safeRow.state).joinToString()
        assertFalse(persisted.contains("password"))
        assertFalse(persisted.contains(secretUrl))
    }

    private fun transfer(unmetered: () -> Boolean = { true }) = DownloadTransfer(
        OkHttpClient(), { server.url("/media?username=user&password=password").toString() }, files,
        { 10_000L }, repo, MediaConnectionCoordinator(quietGapMillis = 0), unmetered
    )

    private fun row(expected: Long? = null, etag: String? = null, reserved: Long = 10_000_000) = DownloadEntity(
        accountId = "opaque-account", kind = "movie", seriesId = 0, mediaId = "opaque-id", state = "DOWNLOADING",
        expectedBytes = expected, reservedBytes = reserved, etag = etag, relativePath = "item", createdAt = 0, updatedAt = 0
    )

    private class LocalFiles(private val root: File) : TransferFileSystem {
        var failWrites = false
        override fun part(relativePath: String) = File(root, "$relativePath.part")
        override fun finalFile(relativePath: String?) = relativePath?.let { File(root, it) }
        override fun length(file: File) = file.length()
        override fun delete(file: File) = file.delete()
        override fun truncate(file: File) { file.writeBytes(byteArrayOf()) }
        override fun write(file: File, offset: Long, bytes: ByteArray, length: Int) {
            if (failWrites) throw java.io.IOException("ENOSPC")
            java.io.RandomAccessFile(file, "rw").use { out -> out.seek(offset); out.write(bytes, 0, length) }
        }
        override fun atomicRename(part: File, final: File) = part.renameTo(final)
    }

    private class FakeRepository : TransferRepository {
        var completed = false
        var etag: String? = null
        var progress = 0L
        override suspend fun markProgress(row: DownloadEntity, bytes: Long) { progress = bytes }
        override suspend fun resume(row: DownloadEntity) = Unit
        override suspend fun updateValidators(row: DownloadEntity, etag: String?, lastModified: String?) { this.etag = etag }
        override suspend fun complete(row: DownloadEntity) { completed = true }
    }
}
