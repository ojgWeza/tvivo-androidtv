package com.dev.Tvivo.playback

import android.content.Context
import android.app.UiModeManager
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dev.Tvivo.playback.PlaybackMode
import com.dev.Tvivo.playback.PlaybackModePreferences
import com.dev.Tvivo.playback.PreferredAudio
import com.dev.Tvivo.playback.PreferredSubtitles
import com.dev.Tvivo.playback.TrackPreferences
import com.dev.Tvivo.ui.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playerPreferences by preferencesDataStore("player_preferences")

/** Non-secret playback choices shared by Settings and the playback service. */
class PlayerPreferencesStore(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.playerPreferences
    val themeMode: Flow<ThemeMode> = store.data.map { ThemeMode.fromPreference(it[THEME_MODE]) }
    val modes: Flow<PlaybackModePreferences> = store.data.map { prefs ->
        PlaybackModePreferences(
            mode(prefs[EPISODE_MODE], PlaybackMode.Next),
            mode(prefs[MOVIE_MODE], PlaybackMode.Off),
        )
    }
    val tracks: Flow<TrackPreferences> = store.data.map { prefs ->
        TrackPreferences(
            enumValue(prefs[AUDIO], PreferredAudio.Auto),
            enumValue(prefs[SUBTITLE], PreferredSubtitles.Off),
        )
    }

    suspend fun setEpisodeMode(value: PlaybackMode) { store.edit { it[EPISODE_MODE] = value.name } }
    suspend fun setMovieMode(value: PlaybackMode) { store.edit { it[MOVIE_MODE] = value.name } }
    suspend fun setAudio(value: PreferredAudio) { store.edit { it[AUDIO] = value.name } }
    suspend fun setSubtitles(value: PreferredSubtitles) { store.edit { it[SUBTITLE] = value.name } }
    suspend fun setThemeMode(value: ThemeMode) {
        store.edit { it[THEME_MODE] = value.toPreference() }
        syncSystemTheme(value)
    }

    /** Android 12+ persists this per-app mode before the next cold-start splash is drawn. */
    fun syncSystemTheme(value: ThemeMode) {
        if (Build.VERSION.SDK_INT >= 31)
            appContext.getSystemService(UiModeManager::class.java)
                ?.setApplicationNightMode(applicationNightMode(value))
    }

    private fun mode(raw: String?, default: PlaybackMode) = enumValue(raw, default)
    private inline fun <reified T : Enum<T>> enumValue(raw: String?, default: T): T =
        raw?.let { value -> enumValues<T>().firstOrNull { it.name.equals(value, true) } } ?: default

    private companion object {
        val EPISODE_MODE = stringPreferencesKey("episode_mode")
        val MOVIE_MODE = stringPreferencesKey("movie_mode")
        val AUDIO = stringPreferencesKey("preferred_audio")
        val SUBTITLE = stringPreferencesKey("preferred_subtitles")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }
}

internal fun applicationNightMode(value: ThemeMode): Int = when (value) {
    ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
    ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
}
