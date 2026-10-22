package com.dev.Tvivo.ui.browse

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import com.dev.Tvivo.ui.BrowseUiHarness
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-mdpi")
class BrowseDpadComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rightEntersGridAndLeftReturnsToSelectedRailRow() {
        compose.setContent { BrowseUiHarness() }
        val selected = compose.onNodeWithTag("category-rail-row-movies")
        selected.requestNodeFocus()
        selected.assertIsFocused()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("catalog-item-1").assertExists() }.isSuccess
        }
        selected.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("catalog-item-1").focusedOrFalse()
        }
        compose.onNodeWithTag("catalog-item-1").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitUntil(5_000) { selected.focusedOrFalse() }
    }

    @Test fun upFromFirstRailRowReachesFilter() {
        compose.setContent { BrowseUiHarness() }
        val selected = compose.onNodeWithTag("category-rail-row-movies")
        selected.requestNodeFocus()
        selected.assertIsFocused()
        selected.performKeyInput { pressKey(Key.DirectionUp) }
        val first = compose.onNodeWithTag("category-rail-row-all")
        compose.waitUntil(5_000) { first.focusedOrFalse() }
        first.performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("category-rail-filter").focusedOrFalse()
        }
    }

    @Test fun rightWithUnattachedGridRequesterDoesNotCrash() {
        compose.setContent { BrowseUiHarness(showGrid = false) }
        val selected = compose.onNodeWithTag("category-rail-row-movies")
        selected.requestNodeFocus()
        selected.assertIsFocused()
        selected.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        selected.assertExists()
    }
}

private fun SemanticsNodeInteraction.focusedOrFalse(): Boolean = runCatching {
    assertIsFocused()
    true
}.getOrDefault(false)

private fun SemanticsNodeInteraction.requestNodeFocus() = performSemanticsAction(SemanticsActions.RequestFocus) { it() }
