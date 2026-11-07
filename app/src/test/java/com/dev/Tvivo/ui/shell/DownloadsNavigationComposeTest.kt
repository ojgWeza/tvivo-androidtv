package com.dev.Tvivo.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dev.Tvivo.home.ShellTab
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.LocalWindowSizeClass
import com.dev.Tvivo.ui.adaptive.WindowSizeClass
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadsNavigationComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneMoreOpensDownloadsAndMarksMoreSelected() {
        var selected by mutableStateOf(ShellTab.MyTvivo)
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides false,
                    LocalWindowSizeClass provides WindowSizeClass.Compact) {
                    AppShell(selected, { selected = it }) {}
                }
            }
        }
        compose.onNodeWithTag("shell-More").performClick()
        compose.onNodeWithTag("more-downloads").performClick()
        compose.runOnIdle { assertEquals(ShellTab.Downloads, selected) }
        compose.onNodeWithTag("shell-More").assertIsSelected()
        compose.onNodeWithTag("shell-Downloads").assertDoesNotExist()
    }

    @Test fun phoneMoreKeepsSettingsEntry() {
        var selected by mutableStateOf(ShellTab.MyTvivo)
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides false,
                    LocalWindowSizeClass provides WindowSizeClass.Compact) {
                    AppShell(selected, { selected = it }) {}
                }
            }
        }
        compose.onNodeWithTag("shell-More").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.runOnIdle { assertEquals(ShellTab.Settings, selected) }
    }

    @Test fun tabletShowsDownloadsDestinationAndSelectedState() {
        var selected by mutableStateOf(ShellTab.MyTvivo)
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides false,
                    LocalWindowSizeClass provides WindowSizeClass.Expanded) {
                    AppShell(selected, { selected = it }) {}
                }
            }
        }
        compose.onNodeWithTag("shell-Downloads").assertExists().performClick()
        compose.runOnIdle { assertEquals(ShellTab.Downloads, selected) }
        compose.onNodeWithTag("shell-Downloads").assertIsSelected()
    }

    @Test fun televisionDoesNotShowDownloadsDestination() {
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides true) {
                    AppShell(ShellTab.MyTvivo, {}) {}
                }
            }
        }
        compose.onNodeWithTag("shell-Downloads").assertDoesNotExist()
    }
}
