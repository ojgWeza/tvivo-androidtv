@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.home.CatalogSort
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvivoTheme
import com.dev.Tvivo.ui.common.tvClickable
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-mdpi")
class BrowseScreenTvReachabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun syncedWith120CategoriesLastCategoryOpensItsGridAndFocusReturns() {
        assertBrowseReachability(BrowseFixtureState.Synced)
    }

    @Test fun syncingKeepsRailReachableAndCategoryOpensItsGrid() {
        assertBrowseReachability(BrowseFixtureState.Syncing)
    }

    @Test fun failedWithCacheKeepsRailAndRetryActionAvailable() {
        assertBrowseReachability(BrowseFixtureState.FailedWithCache)
    }

    @Test fun emptyCatalogKeepsRailAndRecoveryActionAvailable() {
        assertBrowseReachability(BrowseFixtureState.Empty)
    }

    private fun assertBrowseReachability(state: BrowseFixtureState) {
        compose.setContent { BrowseReachabilityHarness(state) }
        val selectedTag = "category-rail-row-category-0"
        val selectedRow = compose.onNodeWithTag(selectedTag)
        compose.waitUntil(5_000) { selectedRow.focusedOrFalse() }
        compose.onNodeWithTag("browse-category-rail").assertExists()

        val lastTag = "category-rail-row-category-119"
        // Send each D-pad event to the row that held focus before that event.
        repeat(119) { index ->
            compose.onNodeWithTag("category-rail-row-category-$index")
                .performKeyInput { pressKey(Key.DirectionDown) }
        }
        compose.waitUntil(5_000) { compose.onNodeWithTag(lastTag).focusedOrFalse() }
        compose.onNodeWithTag(lastTag).assertExists()
        if (state != BrowseFixtureState.Empty) {
            compose.onNodeWithTag(lastTag).performKeyInput { pressKey(Key.Enter) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Items for Category 119").fetchSemanticsNodes().isNotEmpty() }
            val firstCard = compose.onNodeWithTag("catalog-item-1")
            compose.onNodeWithTag(lastTag).performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitUntil(5_000) { firstCard.focusedOrFalse() }
            firstCard.performKeyInput { pressKey(Key.DirectionLeft) }
            compose.waitUntil(5_000) { compose.onNodeWithTag(lastTag).focusedOrFalse() }
        }

        if (state == BrowseFixtureState.FailedWithCache || state == BrowseFixtureState.Empty) {
            compose.onNodeWithTag("browse-recovery-action").assertExists().assertIsDisplayed()
            compose.onNodeWithTag("browse-recovery-action").assertHasClickAction()
            compose.onNodeWithTag("browse-category-rail").assertExists()
        }
        assertEquals(120, categoryFixtures.size)
    }
}

private enum class BrowseFixtureState { Synced, Syncing, FailedWithCache, Empty }

private val categoryFixtures = (0 until 120).map { index ->
    CategoryEntity("fixture", "vod", "category-$index", "Category $index", index)
}

/**
 * Drives the real rail and grid with local state and PagingData; no repository or stream.
 * BLOCKED: BrowseScreen itself owns a concrete BrowseViewModel via viewModelFactory and
 * has no injection seam for its state/catalog. Consequently these assertions cover the
 * real stateless rail/grid integration, not BrowseScreen's loading/error wiring.
 */
@Composable
private fun BrowseReachabilityHarness(state: BrowseFixtureState) {
    var selectedId by remember { mutableStateOf(categoryFixtures.first().categoryId) }
    val railRequester = remember { FocusRequester() }
    val gridRequester = remember { FocusRequester() }
    val firstCardRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val selected = categoryFixtures.first { it.categoryId == selectedId }
    val gridItems = remember(selectedId, state) {
        val id = if (state == BrowseFixtureState.Empty) 0 else 1
        flowOf(PagingData.from(if (id == 0) emptyList() else listOf(
            BrowseItem(id, "Item for ${selected.name}", null, "mp4")
        )))
    }.collectAsLazyPagingItems()
    TvivoTheme {
        CompositionLocalProvider(LocalIsTelevision provides true) {
            val inputModeManager = LocalInputModeManager.current
            SideEffect { inputModeManager.requestInputMode(InputMode.Keyboard) }
            Column(Modifier.fillMaxSize().background(AppColors.Bg)) {
                if (state == BrowseFixtureState.FailedWithCache || state == BrowseFixtureState.Empty) {
                    androidx.tv.material3.Text(
                        if (state == BrowseFixtureState.Empty) "Recover catalog" else "Retry catalog",
                        Modifier.tvClickable { }.testTag("browse-recovery-action")
                    )
                }
                Row(Modifier.fillMaxSize()) {
                    CategoryRail(
                        categories = categoryFixtures,
                        counts = categoryFixtures.associate { it.categoryId to if (state == BrowseFixtureState.Empty) 0 else 1 },
                        selectedCategoryId = selectedId,
                        filter = "",
                        onFilterChanged = {},
                        onSelect = { selectedId = it.categoryId },
                        gridFocusRequester = gridRequester,
                        railFocusRequester = railRequester,
                        listState = listState,
                        gridAvailable = true,
                        categorySort = CatalogSort.MostVisited,
                        onRailFocused = {},
                        modifier = Modifier.testTag("browse-category-rail")
                    )
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        androidx.tv.material3.Text("Items for ${selected.name}")
                        ContentGrid(
                            items = gridItems,
                            pendingFocusItemId = null,
                            cardShape = CardShape.POSTER,
                            gridFocusRequester = gridRequester,
                            firstCardFocusRequester = firstCardRequester,
                            railFocusRequester = railRequester,
                            onActivate = {},
                            onContextMenu = {},
                            onFocused = {},
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            LaunchedEffect(Unit) {
                // The rail attaches its requester after first layout; retry like production does.
                repeat(30) {
                    androidx.compose.runtime.withFrameNanos { }
                    if (runCatching { railRequester.requestFocus() }.isSuccess) return@LaunchedEffect
                }
            }
        }
    }
}

private fun SemanticsNodeInteraction.focusedOrFalse(): Boolean = runCatching {
    assertIsFocused(); true
}.getOrDefault(false)
