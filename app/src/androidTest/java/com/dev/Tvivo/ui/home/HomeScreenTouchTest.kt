package com.dev.Tvivo.ui.home

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
            TvivoTheme {
                HomeScreen(
                    lastSelected = null,
                    accountSummary = null,
                    accountWarning = false,
                    isRefreshing = false,
                    refreshMessage = null,
                    onRefreshEverything = {},
                    onSelect = { selected = it },
                    onOpenSettings = {},
                    onExit = {}
                )
            }
        }

        composeRule.onNodeWithText(label).performTouchInput { click() }

        composeRule.runOnIdle {
            assertEquals(expected, selected)
        }
    }
}
