package com.dev.Tvivo.ui.home

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Keeps the handset investigation honest: a genuine touch event must reach the Home tile
 * callback before any orientation or route diagnosis is considered.
 */
class HomeScreenTouchTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun touchOnLiveTileDispatchesItsSelection() {
        assertTouchSelects("Live TV", ContentType.LIVE)
    }

    @Test
    fun touchOnMoviesTileDispatchesItsSelection() {
        assertTouchSelects("Movies", ContentType.MOVIES)
    }

    @Test
    fun touchOnSeriesTileDispatchesItsSelection() {
        assertTouchSelects("Series", ContentType.SERIES)
    }

    private fun assertTouchSelects(label: String, expected: ContentType) {
        var selected: ContentType? = null

        composeRule.setContent {
            // This test targets a handset (the Mi 10 report). The emulator it may run on is a TV,
            // where tile touch is deliberately only logged and activation belongs to the D-pad, so
            // the configuration is forced to a normal (handset) UI mode.
            val handset = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                    Configuration.UI_MODE_TYPE_NORMAL
            }
            CompositionLocalProvider(LocalConfiguration provides handset) {
                TvivoTheme {
                    HomeScreen(
                        accountId = "fixture-home-touch",
                        providerHost = "example.invalid",
                        isRefreshing = false,
                        refreshMessage = null,
                        onRefreshEverything = {},
                        onSelect = { selected = it },
                        onOpenSearch = {},
                        onOpenSettings = {},
                        onOpenCard = { _, _ -> },
                        onPlayCard = { _, _, _ -> },
                        onSeeAll = {},
                        onExit = {}
                    )
                }
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(label).performTouchInput { click() }

        composeRule.runOnIdle {
            assertEquals(expected, selected)
        }
    }
}
