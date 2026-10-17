package com.dev.Tvivo.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import com.dev.Tvivo.data.repository.HomeCard
import com.dev.Tvivo.data.repository.HomeShelf
import com.dev.Tvivo.ui.browse.BrowseItem
import com.dev.Tvivo.ui.components.ShelfRow
import com.dev.Tvivo.ui.components.LandingLoadingSkeleton
import com.dev.Tvivo.ui.theme.TvivoTheme
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.shell.AppShell
import com.dev.Tvivo.ui.shell.LocalShellFocus
import com.dev.Tvivo.home.ShellTab
import com.dev.Tvivo.home.LandingSpec
import com.dev.Tvivo.home.ShelfContentType
import com.dev.Tvivo.home.ShelfLoadState
import com.dev.Tvivo.ui.components.LandingScaffold
import com.dev.Tvivo.ui.components.ShelfFocusRegistry
import com.dev.Tvivo.home.ShelfVerticalDirection
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class ShelfFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun shelfCardCanOwnFocusWithoutFocusRestorer() {
        val card = HomeCard(ContentType.MOVIES, BrowseItem(1, "Fixture title", null, null))
        val shelf = HomeShelf("fixture", "Fixture shelf", ContentType.MOVIES, listOf(card))
        rule.setContent {
            TvivoTheme {
                Column {
                    ShelfRow(shelf, FocusRequester(),
                        onSeeAll = {}, onOpen = {}, onContextMenu = {})
                }
            }
        }
        rule.onNodeWithTag("shelf-card-MOVIES:1")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("shelf-card-MOVIES:1").assertIsFocused()
    }

    /** Compile-only instrumentation coverage for the shared row navigation seam. */
    @Test fun sharedVerticalNavigationRowComposes() {
        val shelf = HomeShelf("nav", "Navigation", ContentType.MOVIES,
            listOf(HomeCard(ContentType.MOVIES,
                BrowseItem(7, "Navigation card", null, null))))
        rule.setContent { TvivoTheme {
            ShelfRow(shelf, FocusRequester(), focusRegistry = ShelfFocusRegistry(),
                onVerticalNavigate = { it == ShelfVerticalDirection.Up },
                onSeeAll = {}, onOpen = {}, onContextMenu = {})
        } }
        rule.onNodeWithTag("shelf-card-MOVIES:7").assertExists()
    }

    @Test fun emptyCoreShelfIsHiddenWithoutHintOrSeeAll() {
        val shelf = HomeShelf("empty", "Favourites", ContentType.MOVIES, emptyList(),
            core = true, emptyHint = "Save a favourite")
        rule.setContent { TvivoTheme {
            ShelfRow(shelf, FocusRequester(),
                onSeeAll = {}, onOpen = {}, onContextMenu = {})
        } }
        rule.onNodeWithText("Favourites").assertDoesNotExist()
        rule.onNodeWithText("See all").assertDoesNotExist()
    }

    @Test fun loadingCoreShelfShowsSharedSkeletonBeforeHint() {
        val shelf = HomeShelf("loading", "Continue watching", ContentType.MOVIES,
            emptyList(), core = true, emptyHint = "Nothing yet")
        rule.setContent { TvivoTheme {
            ShelfRow(shelf, FocusRequester(), loadState = ShelfLoadState.Loading,
                onSeeAll = {}, onOpen = {}, onContextMenu = {})
        } }
        rule.onNodeWithTag("landing-shelf-loading").assertExists()
        rule.onNodeWithTag("shelf-empty-loading").assertDoesNotExist()
        rule.onNodeWithText("See all").assertDoesNotExist()
    }

    @Test fun posterAndLiveSkeletonsMatchTheirCardImageHeights() {
        rule.setContent { TvivoTheme { LandingLoadingSkeleton() } }
        val posterHeight = rule.onNodeWithTag("landing-shelf-loading")
            .fetchSemanticsNode().boundsInRoot.height / rule.density.density
        assertEquals(165f, posterHeight, 0.1f)

        rule.setContent { TvivoTheme { LandingLoadingSkeleton(channel = true) } }
        val channelHeight = rule.onNodeWithTag("landing-shelf-loading")
            .fetchSemanticsNode().boundsInRoot.height / rule.density.density
        assertEquals(62f, channelHeight, 0.1f)
    }

    @Test fun sharedScaffoldComposesEmptyAndPopulatedShelvesForAllLandingSpecs() {
        val specs = listOf(null, ShelfContentType.Movie, ShelfContentType.Series, ShelfContentType.Live)
        fun shelves(populated: Boolean) = specs.flatMap { type ->
            val spec = if (type == null) LandingSpec.myTvivo() else LandingSpec.forType(type)
            spec.pinned.take(1).mapIndexed { index, pinned ->
                val contentType = when (type) {
                    ShelfContentType.Series -> ContentType.SERIES
                    ShelfContentType.Live -> ContentType.LIVE
                    else -> ContentType.MOVIES
                }
                HomeShelf(pinned.id, "${type ?: "Home"} ${pinned.title}", contentType,
                    if (populated) listOf(HomeCard(contentType,
                        BrowseItem(index + 100, "Fixture ${type ?: "Home"}", null, null))) else emptyList(),
                    core = true, emptyHint = "empty")
            }
        }
        var rows by mutableStateOf(shelves(populated = false))
        rule.setContent {
            TvivoTheme {
                LandingScaffold(state = rememberLazyListState()) {
                    items(rows, key = { it.id }) { shelf ->
                        ShelfRow(shelf, FocusRequester(), onSeeAll = {}, onOpen = {}, onContextMenu = {})
                    }
                }
            }
        }
        rows.forEach { rule.onNodeWithText(it.title).assertDoesNotExist() }
        rule.runOnIdle { rows = shelves(populated = true) }
        rows.forEach { rule.onNodeWithTag("shelf-card-${it.type.name}:${it.cards.single().item.id}").assertExists() }
    }

    @Test fun topBarDownAndFirstShelfUpAreDirect() {
        val card = HomeCard(ContentType.MOVIES, BrowseItem(2, "Fixture", null, null))
        val shelf = HomeShelf("first", "Continue watching", ContentType.MOVIES, listOf(card))
        rule.setContent { TvivoTheme {
            CompositionLocalProvider(LocalIsTelevision provides true) {
                AppShell(ShellTab.MyTvivo, onSelect = {}) {
                    val focus = requireNotNull(LocalShellFocus.current)
                    ShelfRow(shelf, focus.firstContent, focus.topBar,
                        onSeeAll = {}, onOpen = {}, onContextMenu = {})
                }
            }
        } }
        rule.onNodeWithTag("shell-MyTvivo")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("shell-MyTvivo").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("shelf-card-MOVIES:2").assertIsFocused()
        rule.onNodeWithTag("shelf-card-MOVIES:2").performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithTag("shell-MyTvivo").assertIsFocused()
    }
}
