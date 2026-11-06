package com.dev.Tvivo.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Work input intentionally contains no media identity, URL, or credential. */
object DownloadController {
    private const val UNIQUE_WORK = "phone-download-transfer"

    fun kick(context: Context) {
        val request = buildRequest()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    internal fun buildRequest() = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

    suspend fun pause(context: Context, row: com.dev.Tvivo.data.local.entities.DownloadEntity) {
        DownloadTransfer.requestAction(row, DownloadTransfer.UserAction.Pause)
        DownloadDependencies.repository(context).pause(row)
    }

    suspend fun resume(context: Context, row: com.dev.Tvivo.data.local.entities.DownloadEntity) {
        DownloadDependencies.repository(context).resume(row)
        kick(context)
    }

    suspend fun retry(context: Context, row: com.dev.Tvivo.data.local.entities.DownloadEntity): DownloadRepository.EnqueueResult {
        val relativePath = requireNotNull(row.relativePath) { "Failed download has no local path" }
        val result = DownloadDependencies.repository(context).enqueue(listOf(DownloadRequest(
            accountId = row.accountId, kind = row.kind, seriesId = row.seriesId, mediaId = row.mediaId,
            containerExtension = row.containerExtension, relativePath = relativePath
        )))
        if (result is DownloadRepository.EnqueueResult.Accepted) kick(context)
        return result
    }

    suspend fun cancel(context: Context, row: com.dev.Tvivo.data.local.entities.DownloadEntity) {
        DownloadTransfer.requestAction(row, DownloadTransfer.UserAction.Cancel)
        DownloadDependencies.repository(context).cancel(row, deletePartial = true)
        kick(context)
    }

    suspend fun reconcileAndKick(context: Context) {
        val repo = DownloadDependencies.repository(context)
        val rows = repo.reconcileAfterProcessDeath()
        if (rows.any { it.state == "QUEUED" || it.state == "DOWNLOADING" }) kick(context)
    }
}
