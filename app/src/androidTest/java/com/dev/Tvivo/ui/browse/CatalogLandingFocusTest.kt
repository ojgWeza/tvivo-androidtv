package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.tv.material3.Text
import com.dev.Tvivo.data.repository.HomeCard
import com.dev.Tvivo.data.repository.HomeShelf
import com.dev.Tvivo.ui.components.ShelfRow
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.home.CatalogSort
import com.dev.Tvivo.home.CatalogLandingPolicy
import com.dev.Tvivo.home.ShellTab
import com.dev.Tvivo.ui.shell.AppShell
import com.dev.Tvivo.ui.shell.LocalShellFocus
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class CatalogLandingFocusTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun leftAtLeadingShelfCardRevealsCategoryPanel() {
        var panelOpen by mutableStateOf(false)
        val card = HomeCard(ContentType.MOVIES, BrowseItem(1, "Film", null, "mp4"))
        composeRule.setContent {
            TvivoTheme {
                Row {
                    if (panelOpen) Text("Categories panel", modifier = Modifier.testTag("category-panel"))
                    ShelfRow(HomeShelf("10", "Category", ContentType.MOVIES, listOf(card), "10"),
                        FocusRequester(), onSeeAll = {}, onOpen = {},
                        onContextMenu = {}, onLeadingLeft = { panelOpen = true })
                }
            }
        }
        composeRule.onNodeWithTag("shelf-card-MOVIES:1")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithTag("shelf-card-MOVIES:1").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag("category-panel").assertExists()
    }

    @Test fun categoryPanelIncludesAllAndSelectsIt() {
        var selected by mutableStateOf(VirtualFolder.RECENTLY_ADDED.id)
        val categories = VirtualFolder.forContentType(false).mapIndexed { index, folder ->
            folder.toCategory("test", "vod", index)
        }
        composeRule.setContent {
            TvivoTheme {
                CategoryRail(categories, emptyMap(), selected, "", {},
                    { selected = it.categoryId }, FocusRequester(), FocusRequester(), gridAvailable = true)
            }
        }
        composeRule.onNodeWithText("ALL").performClick()
        composeRule.runOnIdle { assertEquals(VirtualFolder.ALL.id, selected) }
    }

    @Test fun seeAllHasAccessibleNameAndAtLeast48DpTargetAndRowsMirrorInRtl() {
        val cards = listOf(
            HomeCard(ContentType.MOVIES, BrowseItem(1, "Film one", null, "mp4")),
            HomeCard(ContentType.MOVIES, BrowseItem(2, "Film two", null, "mp4"))
        )
        val shelf = HomeShelf("category", "A very long Arabic category label", ContentType.MOVIES,
            cards, totalCount = 21)
        composeRule.setContent {
            TvivoTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl
                ) {
                    ShelfRow(shelf, FocusRequester(), onSeeAll = {}, onOpen = {}, onContextMenu = {})
                }
            }
        }
        val seeAll = composeRule.onNodeWithContentDescription("See all ${shelf.title}")
        seeAll.assertExists()
        val targetHeightDp = seeAll.fetchSemanticsNode().boundsInRoot.height / composeRule.density.density
        assertTrue("See all target must be at least 48 dp", targetHeightDp >= 48f)
        composeRule.onNodeWithText(shelf.title).assertExists()
        val first = composeRule.onNodeWithTag("shelf-card-MOVIES:1")
            .fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithTag("shelf-card-MOVIES:2")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("RTL should mirror the first card to the right", first.left > second.left)
        composeRule.onNodeWithTag("shelf-card-MOVIES:1")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithTag("shelf-card-MOVIES:1").performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag("shelf-card-MOVIES:2").assertIsFocused()
    }

    @Test fun downFromSortWithUnloadedCategoryRowDoesNotCrash() {
        var route = "Movies"
        composeRule.setContent {
            TvivoTheme {
                AppShell(ShellTab.Movies, {}) {
                    val shell = requireNotNull(LocalShellFocus.current)
                    LazyColumn {
                        item {
                            CatalogSortControl(CatalogSort.AlphabeticalAsc, {},
                                options = CatalogLandingPolicy.openedListSortOptions(),
                                scopeLabel = "Item order",
                                rowRequester = shell.firstContent)
                        }
                        // Mirrors a category preview that has not loaded yet: its row
                        // identity is known to the landing, but it has no focus node.
                        item(key = "unloaded-category") { }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("sort-AlphabeticalAsc").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        repeat(20) {
            composeRule.onNodeWithTag("sort-AlphabeticalAsc").performKeyInput {
                pressKey(Key.DirectionDown)
            }
        }
        composeRule.runOnIdle { assertEquals("Movies", route) }
    }
}
