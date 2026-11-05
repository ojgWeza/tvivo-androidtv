package com.dev.Tvivo.download

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.downloadPreferences by preferencesDataStore("download_preferences")

data class DownloadPreferences(val capGb: Int = DownloadQuota.DEFAULT_CAP_GB, val wifiOnly: Boolean = true) {
    val capBytes: Long get() = DownloadQuota.capBytes(capGb)
}

class DownloadPreferencesStore(context: Context) {
    private val store = context.applicationContext.downloadPreferences
    val preferences: Flow<DownloadPreferences> = store.data.map { prefs ->
        DownloadPreferences(DownloadQuota.clampCapGb(prefs[CAP_GB] ?: DownloadQuota.DEFAULT_CAP_GB), prefs[WIFI_ONLY] ?: true)
    }
    suspend fun setCapGb(value: Int) { store.edit { it[CAP_GB] = DownloadQuota.clampCapGb(value) } }
    suspend fun setWifiOnly(value: Boolean) { store.edit { it[WIFI_ONLY] = value } }

    private companion object {
        val CAP_GB = intPreferencesKey("cap_gb")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    }
}
