package com.dev.Tvivo.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dev.Tvivo.MainActivity
import com.dev.Tvivo.R
import com.dev.Tvivo.data.local.entities.DownloadEntity

object DownloadNotifications {
    private const val CHANNEL_ID = "downloads"
    private const val NOTIFICATION_ID = 8107

    fun showPaused(context: Context, row: DownloadEntity) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.downloads_title), NotificationManager.IMPORTANCE_LOW
        ).apply { setSound(null, null) })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_mark)
            .setContentTitle(context.getString(R.string.downloads_title))
            .setContentText(context.getString(R.string.download_paused))
            .setContentIntent(open).setOngoing(false).setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.downloads_resume), action(context, row, DownloadActionReceiver.ACTION_RESUME))
            .addAction(0, context.getString(R.string.download_cancel_action), action(context, row, DownloadActionReceiver.ACTION_CANCEL))
            .build())
    }

    fun dismiss(context: Context) = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)

    private fun action(context: Context, row: DownloadEntity, action: String): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadActionReceiver.EXTRA_ACCOUNT, row.accountId)
            putExtra(DownloadActionReceiver.EXTRA_KIND, row.kind)
            putExtra(DownloadActionReceiver.EXTRA_SERIES, row.seriesId)
            putExtra(DownloadActionReceiver.EXTRA_MEDIA, row.mediaId)
        }
        return PendingIntent.getBroadcast(context, row.hashCode() xor action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
