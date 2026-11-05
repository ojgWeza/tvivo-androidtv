package com.dev.Tvivo.download

import android.content.Context
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.repository.MediaFileSizeRepository
import kotlinx.coroutines.flow.first
import com.dev.Tvivo.data.remote.XtreamApiClient

/** One app-scoped repository/store pair, constructed alongside other repositories. */
object DownloadDependencies {
    @Volatile private var repository: DownloadRepository? = null
    @Volatile private var preferences: DownloadPreferencesStore? = null
    val httpClient by lazy { XtreamApiClient.mediaHttpClient() }

    fun preferences(context: Context): DownloadPreferencesStore = preferences ?: synchronized(this) {
        preferences ?: DownloadPreferencesStore(context.applicationContext).also { preferences = it }
    }

    fun repository(context: Context): DownloadRepository = repository ?: synchronized(this) {
        repository ?: run {
            val db = AppDatabase.get(context.applicationContext)
            val store = preferences(context)
            DownloadRepository(db, db.downloadDao(), MediaFileSizeRepository(db.mediaFileSizeDao()),
                AndroidDownloadFileSystem(context), { store.preferences.first().capBytes })
                .also { repository = it }
        }
    }
}
