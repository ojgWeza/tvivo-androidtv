package com.dev.Tvivo.download

import com.dev.Tvivo.data.local.entities.DownloadEntity
import com.dev.Tvivo.playback.MediaConnectionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Android-free transfer policy. All externally visible strings are supplied by the caller. */
class DownloadTransfer(
    private val client: OkHttpClient,
    private val baseUrl: (DownloadEntity) -> String,
    private val files: TransferFileSystem,
    private val clockMillis: () -> Long,
    private val repository: TransferRepository,
    private val coordinator: MediaConnectionCoordinator,
    private val unmetered: () -> Boolean,
    private val progress: suspend (Long, Long?) -> Unit = { _, _ -> },
    private val progressIntervalMillis: Long = 1_000,
) {
    sealed interface Outcome {
        data object Complete : Outcome
        data object Retry : Outcome
        data object Authorization : Outcome
        data object Storage : Outcome
    }

    suspend fun run(row: DownloadEntity, wifiOnly: Boolean): Outcome {
        val part = files.part(row.relativePath ?: return Outcome.Storage) ?: return Outcome.Storage
        val final = files.finalFile(row.relativePath) ?: return Outcome.Storage
        try {
            coordinator.withSlot(MediaConnectionCoordinator.Kind.DOWNLOAD, row.mediaId.take(80), row.accountId) {
                transfer(row, part, final, wifiOnly)
            }
            return Outcome.Complete
        } catch (_: MediaConnectionCoordinator.Preempted) {
            repository.markProgress(row, files.length(part))
            repository.resume(row)
            return Outcome.Retry
        } catch (e: CancellationException) {
            repository.markProgress(row, files.length(part))
            throw e
        } catch (_: AuthorizationException) {
            return Outcome.Authorization
        } catch (_: StorageException) {
            return Outcome.Storage
        } catch (_: IOException) {
            repository.markProgress(row, files.length(part))
            repository.resume(row)
            return Outcome.Retry
        }
    }

    private suspend fun transfer(row: DownloadEntity, part: File, final: File, wifiOnly: Boolean) {
        var offset = files.length(part)
        val request = Request.Builder().url(baseUrl(row)).apply {
            if (offset > 0) {
                header("Range", "bytes=$offset-")
                (row.etag ?: row.lastModified)?.let { header("If-Range", it) }
            }
        }.build()
        val call = client.newCall(request)
        currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
        val response = withContext(Dispatchers.IO) { call.execute() }
        response.use { res ->
            when {
                res.code == 401 || res.code == 403 -> throw AuthorizationException()
                res.code in 500..599 -> throw IOException("server")
                res.code == 416 -> {
                    val serverLength = res.header("Content-Range")?.substringAfter("*/")?.toLongOrNull()
                    val expected = row.expectedBytes ?: serverLength
                    if (expected != null && offset == expected) { finish(row, part, final, offset); return }
                    files.delete(part)
                    throw IOException("range")
                }
                res.code != 200 && res.code != 206 -> throw AuthorizationException()
            }
            val append = res.code == 206 && offset > 0
            if (offset > 0 && res.code == 200) { files.truncate(part); offset = 0 }
            val body = res.body ?: throw IOException("empty")
            val total = if (res.code == 206) res.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                else body.contentLength().takeIf { it >= 0 }
            val maxBytes = row.reservedBytes + SLACK_BYTES
            if (total != null && total > maxBytes) throw StorageException()
            val etag = res.header("ETag")
            val modified = res.header("Last-Modified")
            val validatorChanged = append && ((row.etag != null && etag != null && row.etag != etag) ||
                (row.etag == null && row.lastModified != null && modified != null && row.lastModified != modified))
            if (validatorChanged) {
                repository.updateValidators(row, etag, modified)
                files.delete(part)
                throw IOException("validator")
            }
            repository.updateValidators(row, etag ?: row.etag, modified ?: row.lastModified)
            val input = body.byteStream()
            try {
                if (!append) files.truncate(part)
                var written = if (append) files.length(part) else 0L
                run {
                    val buffer = ByteArray(64 * 1024)
                    var lastDb = offset
                    var lastNotice = clockMillis()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (wifiOnly && !unmetered()) throw IOException("metered")
                        val count = withContext(Dispatchers.IO) { input.read(buffer) }
                        if (count < 0) break
                        val next = written + count
                        if (next > maxBytes) throw StorageException()
                        try { withContext(Dispatchers.IO) { files.write(part, written, buffer, count) } }
                        catch (failure: IOException) { if (failure.message.orEmpty().contains("space", true) || failure.message.orEmpty().contains("enospc", true)) throw StorageException(); throw failure }
                        written = next
                        val now = clockMillis()
                        if (next - lastDb >= CHECKPOINT_BYTES) { repository.markProgress(row, next); lastDb = next }
                        if (now - lastNotice >= progressIntervalMillis) { progress(next, total); lastNotice = now }
                    }
                }
            } catch (failure: IOException) {
                if (failure is StorageException) throw failure
                if (failure.message.orEmpty().contains("space", true) || failure.message.orEmpty().contains("enospc", true)) throw StorageException()
                throw failure
            }
            val length = files.length(part)
            if ((total != null && length != total) || (row.expectedBytes != null && length != row.expectedBytes)) throw IOException("length")
            finish(row, part, final, length)
        }
    }

    private suspend fun finish(row: DownloadEntity, part: File, final: File, length: Long) {
        repository.markProgress(row, length)
        if (!files.atomicRename(part, final)) throw StorageException()
        repository.complete(row)
        progress(length, length)
    }

    private class AuthorizationException : IOException()
    private class StorageException : IOException()

    companion object { private const val SLACK_BYTES = 1_000_000L; private const val CHECKPOINT_BYTES = 1_000_000L }
}

interface TransferFileSystem {
    fun part(relativePath: String): File?
    fun finalFile(relativePath: String?): File?
    fun length(file: File): Long
    fun delete(file: File): Boolean
    fun truncate(file: File)
    fun write(file: File, offset: Long, bytes: ByteArray, length: Int)
    fun atomicRename(part: File, final: File): Boolean
}

interface TransferRepository {
    suspend fun markProgress(row: DownloadEntity, bytes: Long)
    suspend fun resume(row: DownloadEntity)
    suspend fun updateValidators(row: DownloadEntity, etag: String?, lastModified: String?)
    suspend fun complete(row: DownloadEntity)
}
