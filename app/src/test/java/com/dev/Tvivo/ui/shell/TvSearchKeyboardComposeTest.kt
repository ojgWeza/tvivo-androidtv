package com.dev.Tvivo.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.theme.TvivoTheme
import com.dev.Tvivo.ui.theme.AppColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.captureRoboImage

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TvSearchKeyboardComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dpadMovesAcrossKeyboardAndDoneMovesFocusToResults() {
        val resultFocus = FocusRequester()
        var submitted = false
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides true) {
                    val inputModeManager = LocalInputModeManager.current
                    SideEffect { inputModeManager.requestInputMode(InputMode.Keyboard) }
                    Column(Modifier.fillMaxSize()) {
                        TvSearchKeyboard(
                            query = "film", onQueryChanged = {}, recentSearches = emptyList(),
                            onRecentSelected = {}, onClearRecents = {},
                            onDone = { submitted = true; resultFocus.requestFocus() },
                            firstKeyFocus = remember { FocusRequester() }, resultsFocus = resultFocus,
                            onKeyboardFocused = {}
                        )
                        Text("Results", Modifier.focusRequester(resultFocus).focusable().testTag("search-results-target"))
                    }
                }
            }
        }
        compose.onNodeWithTag("tv-search-key-0").requestNodeFocus()
        compose.onNodeWithTag("tv-search-key-0").assertIsFocused()
        compose.onNodeWithTag("tv-search-key-0").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("tv-search-key-1").assertIsFocused()
        compose.onNodeWithTag("tv-search-key-40").performClick()
        assertEquals(true, submitted)
        compose.onNodeWithTag("search-results-target").assertIsFocused()
    }

    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi")
    fun englishKeyboardSnapshotAtFontScale1() {
        compose.setContent { KeyboardSnapshot(fontScale = 1f) }
        compose.onNodeWithTag("snapshot-root").captureRoboImage()
    }

    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi")
    fun englishKeyboardSnapshotAtFontScale15() {
        compose.setContent { KeyboardSnapshot(fontScale = 1.5f) }
        compose.onNodeWithTag("snapshot-root").captureRoboImage()
    }

    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi")
    fun arabicKeyboardSnapshotAtFontScale1() {
        compose.setContent { KeyboardSnapshot(fontScale = 1f, arabic = true) }
        compose.onNodeWithTag("snapshot-root").captureRoboImage()
    }

    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi")
    fun arabicKeyboardSnapshotAtFontScale15() {
        compose.setContent { KeyboardSnapshot(fontScale = 1.5f, arabic = true) }
        compose.onNodeWithTag("snapshot-root").captureRoboImage()
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun KeyboardSnapshot(fontScale: Float, arabic: Boolean = false) {
    val density = LocalDensity.current
    var query by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val results = remember { FocusRequester() }
    TvivoTheme {
        CompositionLocalProvider(
            LocalIsTelevision provides true,
            LocalDensity provides Density(density.density, fontScale),
            LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr
        ) {
            val inputModeManager = LocalInputModeManager.current
            SideEffect { inputModeManager.requestInputMode(InputMode.Keyboard) }
            Column(Modifier.fillMaxSize().background(AppColors.Bg).testTag("snapshot-root")) {
                TvSearchKeyboard(query, { query = it }, emptyList(), {}, {}, { results.requestFocus() }, first, results, {},
                    if (arabic) TvSearchLanguage.ARABIC else TvSearchLanguage.ENGLISH)
                Text("Fixture results", Modifier.focusRequester(results).focusable())
            }
        }
    }
}

private fun SemanticsNodeInteraction.focusedOrFalse(): Boolean = runCatching {
    assertIsFocused()
    true
}.getOrDefault(false)

private fun SemanticsNodeInteraction.requestNodeFocus() = performSemanticsAction(SemanticsActions.RequestFocus) { it() }
