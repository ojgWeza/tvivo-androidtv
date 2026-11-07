package com.dev.Tvivo.download

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.dev.Tvivo.data.local.entities.DownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadNotificationsTest {
    @Test fun pausedDownloadNotificationOffersResumeAndCancelAndIsNotOngoing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val row = DownloadEntity("acct", "movie", 0, "42", state = "PAUSED", partialBytes = 10,
            relativePath = "movie.part", createdAt = 1, updatedAt = 1)
        DownloadNotifications.showPaused(context, row)
        val notification = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications.single().notification
        assertFalse(notification.flags.and(android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals(listOf("Resume", "Cancel"), notification.actions.map { it.title.toString() })
        DownloadNotifications.dismiss(context)
    }
}
