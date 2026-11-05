package com.dev.Tvivo.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.dev.Tvivo.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val account = intent.getStringExtra(EXTRA_ACCOUNT) ?: return
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return
        val series = intent.getIntExtra(EXTRA_SERIES, 0)
        val media = intent.getStringExtra(EXTRA_MEDIA) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext
                val row = AppDatabase.get(app).downloadDao().get(account, kind, series, media) ?: return@launch
                when (intent.action) {
                    ACTION_PAUSE -> { WorkManager.getInstance(app).cancelUniqueWork("phone-download-transfer"); DownloadDependencies.repository(app).pause(row) }
                    ACTION_CANCEL -> DownloadController.cancel(app, row)
                }
            } finally { pending.finish() }
        }
    }
    companion object {
        const val ACTION_PAUSE = "com.dev.Tvivo.download.PAUSE"
        const val ACTION_CANCEL = "com.dev.Tvivo.download.CANCEL"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SERIES = "series"
        const val EXTRA_MEDIA = "media"
    }
}
