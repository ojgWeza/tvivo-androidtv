package com.dev.Tvivo.playback

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.app.UiModeManager
import com.dev.Tvivo.ui.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerThemePreferenceTest {
    @Test fun savedThemeMapsToPlatformSplashNightMode() {
        assertEquals(UiModeManager.MODE_NIGHT_YES, applicationNightMode(ThemeMode.Dark))
        assertEquals(UiModeManager.MODE_NIGHT_NO, applicationNightMode(ThemeMode.Light))
    }

    @Test fun defaultsDarkAndPersistsAcrossStoreInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.preferencesDataStoreFile("player_preferences").delete()
        assertEquals(ThemeMode.Dark, PlayerPreferencesStore(context).themeMode.first())

        PlayerPreferencesStore(context).setThemeMode(ThemeMode.Light)

        // A fresh store instance is used after the equivalent of an account switch: this
        // preference belongs to the app, never to the selected account.
        assertEquals(ThemeMode.Light, PlayerPreferencesStore(context).themeMode.first())
    }
}
