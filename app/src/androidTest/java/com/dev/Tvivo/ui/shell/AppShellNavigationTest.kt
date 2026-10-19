package com.dev.Tvivo.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import com.dev.Tvivo.home.ShellTab
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.LocalWindowSizeClass
import com.dev.Tvivo.ui.adaptive.WindowSizeClass
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppShellNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun phoneBottomNavigationSelectsMoviesAndSettingsWithSearchInTopBar() {
        var selected by mutableStateOf(ShellTab.MyTvivo)
        rule.setContent {
            TvivoTheme {
                CompositionLocalProvider(
                    LocalIsTelevision provides false,
                    LocalWindowSizeClass provides WindowSizeClass.Compact
                ) {
                    AppShell(selected, { selected = it }) {}
                }
            }
        }
        rule.onNodeWithTag("shell-Movies").performClick()
        rule.runOnIdle { assertEquals(ShellTab.Movies, selected) }
        rule.onNodeWithTag("shell-Settings").performClick()
        rule.runOnIdle { assertEquals(ShellTab.Settings, selected) }
        rule.onNodeWithTag("shell-Search").assertDoesNotExist()
        rule.onNodeWithTag("shell-action-Search").performClick()
        rule.runOnIdle { assertEquals(ShellTab.Search, selected) }
    }

    @Test fun tvTopBarIncludesSettings() {
        rule.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides true) {
                    AppShell(ShellTab.MyTvivo, {}) {}
                }
            }
        }
        rule.onNodeWithTag("shell-Settings").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun downFromTopBarWithNoContentIsSafe() {
        rule.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides true) {
                    AppShell(ShellTab.Movies, {}) { }
                }
            }
        }
        rule.onNodeWithTag("shell-Movies")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        repeat(20) {
            rule.onNodeWithTag("shell-Movies").performKeyInput { pressKey(Key.DirectionDown) }
        }
        rule.onNodeWithTag("shell-Movies").assertExists()
    }
}
