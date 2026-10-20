package com.dev.Tvivo.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.repository.HomeCard
import com.dev.Tvivo.data.repository.HomeRepository
import com.dev.Tvivo.data.repository.HomeShelf
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.common.dpadFieldNavigation
import com.dev.Tvivo.ui.common.IconPill
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.activity.compose.BackHandler
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.SearchBackAction
import com.dev.Tvivo.ui.searchBackAction
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import com.dev.Tvivo.R
import com.dev.Tvivo.ui.browse.SearchContentCard
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    accountId: String,
    query: String,
    onQueryChanged: (String) -> Unit,
    pendingFocusCardKey: String? = null,
    onPendingFocusConsumed: () -> Unit = {},
    onOpen: (HomeCard) -> Unit,
    onBrowse: (ContentType) -> Unit
) {
    val context = LocalContext.current
    val repository = remember(accountId) { HomeRepository(AppDatabase.get(context), accountId) }
    var typeFilter by rememberSaveable(accountId) { mutableStateOf<ContentType?>(null) }
    var shelves by remember { mutableStateOf<List<HomeShelf>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchLoaded by remember(accountId) { mutableStateOf(false) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = LocalShellFocus.current?.firstContent ?: remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Back with text clears it while Search stays open. Empty Back reaches the shell's
    // existing handler and returns to My Tvivo.
    BackHandler(enabled = searchBackAction(query) == SearchBackAction.Clear) { onQueryChanged("") }
    LaunchedEffect(accountId, pendingFocusCardKey, query.isEmpty()) {
        if (pendingFocusCardKey == null) runCatching { fieldFocus.requestFocus() }
    }
    LaunchedEffect(accountId, query) {
        if (query.isBlank()) {
            shelves = emptyList()
            searching = false
            searchLoaded = true
        } else {
            searching = true
            searchLoaded = false
            delay(300)
            shelves = repository.search(query)
            searching = false
            searchLoaded = true
        }
    }
    val visibleShelves = shelves.filter { typeFilter == null || it.type == typeFilter }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val television = LocalIsTelevision.current
    val columns = searchGridColumnCount(television, LocalConfiguration.current.screenWidthDp)
    LaunchedEffect(pendingFocusCardKey, visibleShelves, searchLoaded) {
        var offset = 0
        val index = visibleShelves.firstNotNullOfOrNull { shelf ->
            val cardIndex = shelf.cards.indexOfFirst { it.key == pendingFocusCardKey }
            (offset + 1 + cardIndex).takeIf { cardIndex >= 0 }.also { offset += shelf.cards.size + 1 }
        }
        if (index != null) {
            gridState.scrollToItem(index)
            repeat(5) {
                androidx.compose.runtime.withFrameNanos {}
                if (runCatching { restoreFocus.requestFocus() }.isSuccess) {
                    onPendingFocusConsumed()
                    return@LaunchedEffect
                }
            }
        }
        else if (pendingFocusCardKey != null && searchLoaded && !searching) onPendingFocusConsumed()
    }
    LaunchedEffect(gridState) {
        androidx.compose.runtime.snapshotFlow { gridState.isScrollInProgress to searchFieldFocused }
            .collect { (scrolling, fieldFocused) ->
            if (scrolling && fieldFocused) {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }
    }
    val screenPadding = if (television) 48.dp else 24.dp
    Column(Modifier.fillMaxSize().background(AppColors.Bg).padding(screenPadding)) {
        Text(stringResource(R.string.search_title), color = AppColors.Ink, style = TvType.headline)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        BasicTextField(
            value = query,
            onValueChange = {
                onQueryChanged(it)
                if (it.isNotEmpty()) onPendingFocusConsumed()
            },
            singleLine = true,
            textStyle = TextStyle(color = AppColors.Ink, fontSize = TvType.body.fontSize),
            modifier = Modifier.weight(1f).testTag("search-field")
                .focusRequester(fieldFocus)
                .onFocusChanged { searchFieldFocused = it.isFocused }
                .focusProperties { if (query.isNotEmpty()) right = clearFocus }
                .dpadFieldNavigation(focusManager)
                .background(AppColors.Elevated).padding(14.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isBlank()) Text(stringResource(R.string.search_placeholder), color = AppColors.Dim,
                        style = TvType.body)
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            IconPill(Icons.Default.Close, stringResource(R.string.search_clear),
                onClick = { onQueryChanged(""); runCatching { fieldFocus.requestFocus() } },
                modifier = Modifier.focusRequester(clearFocus)
                    .focusProperties { left = fieldFocus }
                    .semantics { contentDescription = context.getString(R.string.search_clear) }
                    .testTag("search-clear"))
        }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null, ContentType.MOVIES, ContentType.SERIES, ContentType.LIVE).forEach { type ->
                val label = stringResource(when (type) {
                    null -> R.string.search_filter_all
                    ContentType.MOVIES -> R.string.search_filter_movies
                    ContentType.SERIES -> R.string.search_filter_series
                    ContentType.LIVE -> R.string.search_filter_live
                })
                Text(label, color = if (typeFilter == type) AppColors.AccentText else AppColors.Ink,
                    style = TvType.label,
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusFrame().tvClickable { typeFilter = type }
                        .padding(horizontal = 10.dp, vertical = 12.dp))
            }
        }
        if (query.isBlank()) {
            Text(stringResource(R.string.search_saved_catalog_hint), color = AppColors.Dim,
                style = TvType.label, modifier = Modifier.padding(top = 12.dp))
        } else if (searching || visibleShelves.isEmpty()) {
            Text(stringResource(if (searching) R.string.searching_saved_titles else R.string.search_no_results),
                color = AppColors.Dim, style = TvType.body,
                modifier = Modifier.padding(top = 12.dp))
        }
        // A filtered view has exactly one group. Keep its title and full-list action
        // outside the scrolling result grid so they remain visible with the filter row.
        val selectedGroup = visibleShelves.singleOrNull().takeIf { typeFilter != null && query.isNotBlank() }
        selectedGroup?.let { shelf ->
            Row(Modifier.fillMaxWidth().testTag("search-filtered-group-heading"),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(when (shelf.type) {
                    ContentType.MOVIES -> R.string.search_section_movies
                    ContentType.SERIES -> R.string.search_section_series
                    ContentType.LIVE -> R.string.search_section_live
                }), color = AppColors.Ink, style = TvType.title, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.search_see_all), color = AppColors.AccentText, style = TvType.label,
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusFrame().tvClickable { onBrowse(shelf.type) }
                        .padding(8.dp))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth().imePadding().focusGroup()
                .testTag("search-results-grid"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 12.dp, bottom = if (television) 48.dp else 72.dp),
            horizontalArrangement = Arrangement.spacedBy(if (television) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (television) 20.dp else 16.dp)
        ) {
            visibleShelves.forEach { shelf ->
                if (selectedGroup == null) item(key = "search:header:${shelf.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(when (shelf.type) {
                            ContentType.MOVIES -> R.string.search_section_movies
                            ContentType.SERIES -> R.string.search_section_series
                            ContentType.LIVE -> R.string.search_section_live
                        }), color = AppColors.Ink, style = TvType.title)
                        Text(stringResource(R.string.search_see_all), color = AppColors.AccentText, style = TvType.label,
                            modifier = Modifier.heightIn(min = 48.dp).tvFocusFrame().tvClickable { onBrowse(shelf.type) }
                                .padding(8.dp))
                    }
                }
                items(shelf.cards, key = { "search:card:${it.key}" }) { card ->
                    SearchContentCard(card, { selected ->
                        // The first touch on a result is the activation. Hide the IME
                        // as part of that same action so it cannot consume the tap.
                        keyboardController?.hide()
                        onOpen(selected)
                    },
                        focusRequester = restoreFocus.takeIf { card.key == pendingFocusCardKey })
                }
            }
        }
    }
}

/** Stable adaptive breakpoints for search: handset, tablet, and TV widths. */
internal fun searchGridColumnCount(isTelevision: Boolean, widthDp: Int): Int = when {
    isTelevision -> 5
    widthDp >= 840 -> 6
    widthDp >= 600 -> 4
    else -> 2
}
