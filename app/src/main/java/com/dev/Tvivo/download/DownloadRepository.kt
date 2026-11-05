package com.dev.Tvivo.download

import androidx.room.withTransaction
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.dao.DownloadDao
import com.dev.Tvivo.data.local.entities.DownloadEntity
import com.dev.Tvivo.data.repository.MediaFileSizeRepository
import kotlinx.coroutines.flow.Flow

/** File access is injected so quota/restart behavior can be exercised without Android storage. */
interface DownloadFileSystem {
    fun length(relativePath: String): Long?
    fun delete(relativePath: String): Boolean
}

data class DownloadRequest(
    val accountId: String,
    val kind: String,
    val seriesId: Int,
    val mediaId: String,
    val containerExtension: String?,
    /** Opaque app-private relative path. */
    val relativePath: String
)

class DownloadRepository(
    private val database: AppDatabase,
    private val dao: DownloadDao,
    private val sizes: MediaFileSizeRepository,
    private val files: DownloadFileSystem,
    private val capBytes: suspend () -> Long,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    sealed interface EnqueueResult {
        data class Accepted(val evaluation: DownloadQuota.Evaluation.Fits) : EnqueueResult
        data class Rejected(val evaluation: DownloadQuota.Evaluation) : EnqueueResult
    }

    fun observe(accountId: String): Flow<List<DownloadEntity>> = dao.observe(accountId)

    /** Size resolution, quota calculation, duplicate re-check, and reservation insertion are atomic. */
    suspend fun enqueue(batch: List<DownloadRequest>): EnqueueResult = database.withTransaction {
        require(batch.isNotEmpty())
        reconcileLocked()
        batch.forEach(::validateRequest)
        require(batch.map { key(it) }.distinct().size == batch.size) { "Batch contains duplicate media identities" }
        val sizesByKey = batch.associate { request ->
            key(request) to sizes.downloadSize(request.accountId, request.kind, request.seriesId, request.mediaId)
        }
        val existing = batch.associate { key(it) to dao.get(it.accountId, it.kind, it.seriesId, it.mediaId) }
        val resolved = batch.map { request -> sizesByKey.getValue(key(request)) }
        val used = usedBytesLocked()
        val reserved = (dao.sumReservedBytes() - existing.values.filterNotNull()
            .filter { it.state in ACTIVE_STATES }.sumOf { it.reservedBytes }).coerceAtLeast(0)
        val requestSizes = resolved.mapIndexed { index, size ->
            if (existing.getValue(key(batch[index]))?.state == COMPLETED) DownloadQuota.ItemSize(0, true)
            else size?.let { DownloadQuota.ItemSize(it.bytes, it.exact) } ?: DownloadQuota.ItemSize(null, false)
        }
        val evaluation = DownloadQuota.evaluate(requestSizes, capBytes(), used, reserved)
        if (evaluation !is DownloadQuota.Evaluation.Fits) return@withTransaction EnqueueResult.Rejected(evaluation)

        val at = clockMillis()
        batch.forEachIndexed { index, request ->
            val old = existing.getValue(key(request))
            if (old?.state == COMPLETED) return@forEachIndexed
            val size = requireNotNull(resolved[index])
            val reservation = if (size.exact) size.bytes else DownloadQuota.conservativeEstimate(size.bytes)
            dao.upsert(DownloadEntity(
                accountId = request.accountId, kind = request.kind, seriesId = request.seriesId, mediaId = request.mediaId,
                containerExtension = request.containerExtension, state = "QUEUED", expectedBytes = size.bytes,
                partialBytes = old?.partialBytes ?: 0, reservedBytes = reservation,
                etag = old?.etag, lastModified = old?.lastModified, relativePath = request.relativePath,
                createdAt = old?.createdAt ?: at, updatedAt = at
            ))
        }
        EnqueueResult.Accepted(evaluation)
    }

    suspend fun markProgress(row: DownloadEntity, partialBytes: Long) {
        require(partialBytes >= 0)
        dao.updateProgress(row.accountId, row.kind, row.seriesId, row.mediaId, partialBytes, clockMillis())
    }

    suspend fun pause(row: DownloadEntity) = setState(row, "PAUSED", row.reservedBytes)
    suspend fun resume(row: DownloadEntity) = setState(row, "QUEUED", row.reservedBytes)
    suspend fun cancel(row: DownloadEntity, deletePartial: Boolean = true) {
        database.withTransaction {
            if (deletePartial) row.relativePath?.let(files::delete)
            dao.updateState(row.accountId, row.kind, row.seriesId, row.mediaId, "CANCELLED", 0, clockMillis())
            if (deletePartial) dao.updateProgress(row.accountId, row.kind, row.seriesId, row.mediaId, 0, clockMillis())
        }
    }
    suspend fun complete(row: DownloadEntity) = setState(row, "COMPLETED", 0)
    suspend fun fail(row: DownloadEntity, reason: String) {
        require(reason.isNotBlank())
        database.withTransaction { dao.updateState(row.accountId, row.kind, row.seriesId, row.mediaId, "FAILED", 0, clockMillis(), reason) }
    }
    suspend fun remove(row: DownloadEntity) {
        database.withTransaction {
            row.relativePath?.let(files::delete)
            dao.delete(row.accountId, row.kind, row.seriesId, row.mediaId)
        }
    }

    /** Repairs persisted byte counts using filesystem truth; no paths escape the injected adapter. */
    suspend fun reconcile(): List<DownloadEntity> = database.withTransaction { reconcileLocked() }

    private suspend fun reconcileLocked(): List<DownloadEntity> {
        val repaired = mutableListOf<DownloadEntity>()
        dao.getAll().forEach { row ->
            val actual = row.relativePath?.let(files::length)
            val isFinished = row.state in setOf("COMPLETED", "CANCELLED")
            val missingComplete = row.state == "COMPLETED" && (actual == null || actual == 0L)
            val state = if (missingComplete) "FAILED" else row.state
            val partial = if (isFinished || missingComplete) 0 else actual?.coerceAtLeast(0) ?: 0
            val reservation = if (state in ACTIVE_STATES) row.reservedBytes else 0
            val reason = if (missingComplete) "missing" else row.failureReason
            val changed = row.copy(state = state, partialBytes = partial, reservedBytes = reservation,
                updatedAt = if (partial != row.partialBytes || state != row.state || reservation != row.reservedBytes) clockMillis() else row.updatedAt,
                failureReason = reason)
            if (changed != row) dao.upsert(changed)
            repaired += changed
        }
        return repaired
    }

    suspend fun usedBytes(): Long = database.withTransaction { usedBytesLocked() }

    private suspend fun usedBytesLocked(): Long = dao.getAll().sumOf { row ->
        if (row.state == "COMPLETED" || row.state in setOf("QUEUED", "PAUSED", "DOWNLOADING", "FAILED"))
            row.relativePath?.let(files::length)?.coerceAtLeast(0) ?: 0L else 0L
    }

    private suspend fun setState(row: DownloadEntity, state: String, reservation: Long) {
        database.withTransaction { dao.updateState(row.accountId, row.kind, row.seriesId, row.mediaId, state, reservation, clockMillis()) }
    }

    private fun validateRequest(request: DownloadRequest) {
        require(request.kind == "movie" || request.kind == "episode")
        require(if (request.kind == "movie") request.seriesId == 0 else request.seriesId > 0)
        require(request.mediaId.isNotBlank())
        require(request.relativePath.isNotBlank() && "://" !in request.relativePath && '?' !in request.relativePath && '#' !in request.relativePath)
    }
    private fun key(request: DownloadRequest) = "${request.accountId}\u0000${request.kind}\u0000${request.seriesId}\u0000${request.mediaId}"

    private companion object {
        const val COMPLETED = "COMPLETED"
        val ACTIVE_STATES = setOf("QUEUED", "PAUSED", "DOWNLOADING")
    }
}
