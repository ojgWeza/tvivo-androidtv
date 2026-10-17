package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.dev.Tvivo.ui.theme.TvivoTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ContentGridFocusCharacterizationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun railReentryAndDetailReturnRestoreTheSameItemId() {
        var showGrid by mutableStateOf(true)
        var pendingId by mutableStateOf<Int?>(null)
        val railFocus = FocusRequester()
        val gridFocus = FocusRequester()
        val items = (1..5).map { BrowseItem(it, "Item $it", null, "mp4") }
        val paging = flowOf(PagingData.from(items))

        composeRule.setContent {
            TvivoTheme {
                Row {
                    Box(
                        Modifier.size(80.dp).testTag("fixture-rail")
                            .focusRequester(railFocus)
                            .focusProperties { right = gridFocus }
                            .focusable()
                    )
                    if (showGrid) {
                        ContentGrid(
                            items = paging.collectAsLazyPagingItems(),
                            pendingFocusItemId = pendingId,
                            cardShape = CardShape.POSTER,
                            gridFocusRequester = gridFocus,
                            onActivate = { pendingId = it.id; showGrid = false },
                            onContextMenu = {},
                            onFocused = {},
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("catalog-item-2").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("catalog-item-2")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithTag("catalog-item-2").assertIsFocused()

        composeRule.onNodeWithTag("fixture-rail")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithTag("fixture-rail").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("catalog-item-2").assertIsFocused()

        composeRule.runOnIdle { pendingId = 2; showGrid = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showGrid = true }
        composeRule.onNodeWithTag("catalog-item-2").assertIsFocused()
    }
}
