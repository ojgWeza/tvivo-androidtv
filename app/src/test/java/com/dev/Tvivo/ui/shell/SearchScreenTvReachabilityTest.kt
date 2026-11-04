@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.dev.Tvivo.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-mdpi")
class SearchScreenTvReachabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishDpadTypingBottomRowFocusesResultAndOkOpensIt() {
        var opened = ""
        compose.setContent { SearchKeyboardReachabilityHarness(hasResult = true) { opened = it } }

        val firstKey = compose.onNodeWithTag("tv-search-key-0")
        compose.waitUntil(5_000) { firstKey.focusedOrFalse() }
        firstKey.assertIsFocused()
        firstKey.performKeyInput { pressKey(Key.Enter) }
        firstKey.performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("tv-search-key-1").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("tv-search-query").assertTextEquals("ab")

        // English has 41 keys: two Down presses reach row three, then twelve Right
        // presses reach Done (index 40), the rightmost key in that bottom row.
        var focusedIndex = 1
        repeat(2) {
            compose.onNodeWithTag("tv-search-key-$focusedIndex").performKeyInput { pressKey(Key.DirectionDown) }
            focusedIndex += 14
        }
        compose.onNodeWithTag("tv-search-key-$focusedIndex").performKeyInput { pressKey(Key.DirectionLeft) }
        focusedIndex-- // left edge of the bottom keyboard row (index 28)
        repeat(12) {
            compose.onNodeWithTag("tv-search-key-$focusedIndex").performKeyInput { pressKey(Key.DirectionRight) }
            focusedIndex++
        }
        val done = compose.onNodeWithTag("tv-search-key-40")
        compose.waitUntil(5_000) { done.focusedOrFalse() }
        done.performKeyInput { pressKey(Key.DirectionDown) }
        val result = compose.onNodeWithTag("search-fixture-result")
        compose.waitUntil(5_000) { result.focusedOrFalse() }
        result.performKeyInput { pressKey(Key.Enter) }
        assertEquals("fixture-result", opened)
    }

    @Test fun emptyResultsLeaveEnglishKeyboardUsable() {
        compose.setContent { SearchKeyboardReachabilityHarness(hasResult = false) {} }
        val first = compose.onNodeWithTag("tv-search-key-0")
        compose.waitUntil(5_000) { first.focusedOrFalse() }
        first.performKeyInput { pressKey(Key.Enter) }
        first.performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("tv-search-key-1").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("tv-search-query").assertTextEquals("ab")
        compose.onNodeWithTag("search-empty-state").assertExists()
        compose.onNodeWithTag("tv-search-key-1").assertExists()
    }
}

/**
 * Uses the real TV keyboard and a local focusable result. No database/network/provider.
 * BLOCKED: SearchScreen creates HomeRepository(AppDatabase.get(context), accountId)
 * internally and its query flow has no injectable repository/results parameter. These
 * tests therefore cover keyboard-to-result D-pad handoff and activation, not SearchScreen
 * result loading, actual SearchContentCard activation, shell entry, or DB-backed empty UI.
 */
@Composable
private fun SearchKeyboardReachabilityHarness(hasResult: Boolean, onOpen: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val firstKeyFocus = remember { FocusRequester() }
    val resultFocus = remember { FocusRequester() }
    TvivoTheme {
        CompositionLocalProvider(LocalIsTelevision provides true) {
            val inputModeManager = LocalInputModeManager.current
            SideEffect { inputModeManager.requestInputMode(InputMode.Keyboard) }
            Column(Modifier.fillMaxSize().background(AppColors.Bg).padding(32.dp)) {
                TvSearchKeyboard(
                    query = query,
                    onQueryChanged = { query = it },
                    recentSearches = emptyList(),
                    onRecentSelected = {},
                    onClearRecents = {},
                    onDone = { resultFocus.requestFocus() },
                    firstKeyFocus = firstKeyFocus,
                    resultsFocus = resultFocus,
                    onKeyboardFocused = {}
                )
                if (hasResult) {
                    Text(
                        "Fixture result",
                        Modifier.focusRequester(resultFocus).focusable()
                            .clickable { onOpen("fixture-result") }
                            .testTag("search-fixture-result")
                    )
                } else {
                    Text("No results", Modifier.testTag("search-empty-state"))
                }
            }
        }
    }
}

private fun SemanticsNodeInteraction.focusedOrFalse(): Boolean = runCatching {
    assertIsFocused(); true
}.getOrDefault(false)
