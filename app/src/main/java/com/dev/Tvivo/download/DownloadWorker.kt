package com.dev.Tvivo.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.dev.Tvivo.MainActivity
import com.dev.Tvivo.R
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.data.StreamUrlBuilder
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.DownloadEntity
import com.dev.Tvivo.playback.MediaConnectionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val db = AppDatabase.get(context)
    private val repo = DownloadDependencies.repository(context)
    private val dao = db.downloadDao()
    private val lastNotice = AtomicLong(0)
    @Volatile private var activeRow: DownloadEntity? = null

    override suspend fun doWork(): Result {
        setForeground(info(applicationContext.getString(R.string.download_starting), 0, null))
        val row = dao.getAll().firstOrNull { it.state == "QUEUED" } ?: return Result.success()
        activeRow = row
        val wifiOnly = DownloadDependencies.preferences(applicationContext).preferences
        try {
            val prefs = wifiOnly.first()
            if (prefs.wifiOnly && !unmetered()) return Result.retry()
            val credentials = CredentialsStore(applicationContext).load()
                ?: return fail(row, "authorization")
            if (AccountIdentity.of(credentials) != row.accountId) return fail(row, "authorization")
            val extension = row.containerExtension?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "mkv"
            val url = if (row.kind == "movie") StreamUrlBuilder.movie(credentials, row.mediaId.toIntOrNull()
                ?: return fail(row, "authorization"), extension)
                else StreamUrlBuilder.episode(credentials, row.mediaId, extension)
            if (row.relativePath.isNullOrBlank()) return fail(row, "storage")
            dao.updateState(row.accountId, row.kind, row.seriesId, row.mediaId, "DOWNLOADING", row.reservedBytes, System.currentTimeMillis())
            val outcome = DownloadTransfer(
                client = DownloadDependencies.httpClient,
                baseUrl = { url },
                files = AndroidDownloadFileSystem(applicationContext),
                clockMillis = System::currentTimeMillis,
                repository = repo,
                coordinator = MediaConnectionCoordinator.shared,
                unmetered = ::unmetered,
                progress = { bytes, total -> updateNotice(bytes, total) }
            ).run(row, prefs.wifiOnly)
            when (outcome) {
                DownloadTransfer.Outcome.Complete -> {
                    notify(applicationContext.getString(R.string.download_complete), 100, row.expectedBytes)
                    DownloadController.kick(applicationContext)
                    return Result.success()
                }
                DownloadTransfer.Outcome.Retry -> return Result.retry()
                DownloadTransfer.Outcome.Authorization -> return fail(row, "authorization")
                DownloadTransfer.Outcome.Storage -> return fail(row, "storage")
                DownloadTransfer.Outcome.UserPaused -> return Result.success()
                DownloadTransfer.Outcome.UserCancelled -> return Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private suspend fun fail(row: DownloadEntity, reason: String): Result {
        repo.fail(row, reason)
        notify(applicationContext.getString(if (reason == "storage") R.string.download_failed_storage else R.string.download_failed_generic), 0, null)
        return Result.failure()
    }

    private fun unmetered(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    private suspend fun updateNotice(bytes: Long, total: Long?) {
        val now = System.currentTimeMillis()
        if (now - lastNotice.get() >= 1_000) {
            lastNotice.set(now)
            setForeground(info(applicationContext.getString(R.string.download_progress), bytes, total))
        }
    }

    private fun notify(text: String, progress: Int, max: Long?) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channel(nm)
        nm.notify(NOTIFICATION_ID, notification(text, progress, max, ongoing = false))
    }

    private fun info(text: String, bytes: Long, total: Long?) = ForegroundInfo(NOTIFICATION_ID,
        notification(text, if (total != null && total > 0) ((bytes * 100 / total).toInt()) else 0, total, ongoing = true),
        if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)

    private fun notification(text: String, progress: Int, total: Long?, ongoing: Boolean): Notification {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channel(nm)
        val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_mark).setContentTitle(applicationContext.getString(R.string.downloads_title))
            .setContentText(text).setContentIntent(intent).setOngoing(ongoing)
            .setProgress(100, progress.coerceIn(0, 100), total == null).setOnlyAlertOnce(true)
            .apply {
                val row = activeRow
                if (ongoing && row != null) {
                    addAction(0, applicationContext.getString(R.string.download_pause_action), actionIntent(row, DownloadActionReceiver.ACTION_PAUSE))
                    addAction(0, applicationContext.getString(R.string.download_cancel_action), actionIntent(row, DownloadActionReceiver.ACTION_CANCEL))
                }
            }.build()
    }

    private fun actionIntent(row: DownloadEntity, action: String): PendingIntent {
        val intent = Intent(applicationContext, DownloadActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadActionReceiver.EXTRA_ACCOUNT, row.accountId)
            putExtra(DownloadActionReceiver.EXTRA_KIND, row.kind)
            putExtra(DownloadActionReceiver.EXTRA_SERIES, row.seriesId)
            putExtra(DownloadActionReceiver.EXTRA_MEDIA, row.mediaId)
        }
        return PendingIntent.getBroadcast(applicationContext, row.hashCode() xor action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun channel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            applicationContext.getString(R.string.downloads_title), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 8107
        private const val SLACK_BYTES = 1_000_000L
    }
}
