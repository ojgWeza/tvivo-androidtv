package com.dev.Tvivo.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import com.dev.Tvivo.ui.components.ShelfRow
import com.dev.Tvivo.home.ShelfRestoreKey
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
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    accountId: String,
    pendingFocusCardKey: String? = null,
    onPendingFocusConsumed: () -> Unit = {},
    onOpen: (HomeCard) -> Unit,
    onBrowse: (ContentType) -> Unit
) {
    val context = LocalContext.current
    val repository = remember(accountId) { HomeRepository(AppDatabase.get(context), accountId) }
    var query by rememberSaveable(accountId) { mutableStateOf("") }
    var typeFilter by rememberSaveable(accountId) { mutableStateOf<ContentType?>(null) }
    var shelves by remember { mutableStateOf<List<HomeShelf>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchLoaded by remember(accountId) { mutableStateOf(false) }
    val fieldFocus = LocalShellFocus.current?.firstContent ?: remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Back with text clears it while Search stays open. Empty Back reaches the shell's
    // existing handler and returns to My Tvivo.
    BackHandler(enabled = searchBackAction(query) == SearchBackAction.Clear) { query = "" }
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
    val requesters = remember(visibleShelves.map { it.id }) {
        visibleShelves.map { androidx.compose.ui.focus.FocusRequester() }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(pendingFocusCardKey, visibleShelves, searchLoaded) {
        val index = visibleShelves.indexOfFirst { shelf ->
            shelf.cards.any { it.key == pendingFocusCardKey }
        }
        if (index >= 0) listState.scrollToItem(index)
        else if (pendingFocusCardKey != null && searchLoaded && !searching)
            onPendingFocusConsumed()
    }
    Column(Modifier.fillMaxSize().background(AppColors.Bg).padding(24.dp)) {
        Text("Search your library", color = AppColors.Ink, style = TvType.headline)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        BasicTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.isNotEmpty()) onPendingFocusConsumed()
            },
            singleLine = true,
            textStyle = TextStyle(color = AppColors.Ink, fontSize = TvType.body.fontSize),
            modifier = Modifier.weight(1f).testTag("search-field")
                .focusRequester(fieldFocus)
                .focusProperties { if (query.isNotEmpty()) right = clearFocus }
                .dpadFieldNavigation(focusManager)
                .background(AppColors.Elevated).padding(14.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isBlank()) Text("Movies, series or live TV", color = AppColors.Dim,
                        style = TvType.body)
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            IconPill(Icons.Default.Close, "Clear",
                onClick = { query = ""; runCatching { fieldFocus.requestFocus() } },
                modifier = Modifier.focusRequester(clearFocus)
                    .focusProperties { left = fieldFocus }
                    .semantics { contentDescription = "Clear" }
                    .testTag("search-clear"))
        }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null, ContentType.MOVIES, ContentType.SERIES, ContentType.LIVE).forEach { type ->
                val label = type?.label ?: "All"
                Text(label, color = if (typeFilter == type) AppColors.AccentText else AppColors.Ink,
                    style = TvType.label,
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusFrame().tvClickable { typeFilter = type }
                        .padding(horizontal = 10.dp, vertical = 12.dp))
            }
        }
        if (query.isBlank()) {
            Text("Searches saved catalog titles on this device", color = AppColors.Dim,
                style = TvType.label, modifier = Modifier.padding(top = 12.dp))
        } else if (searching || visibleShelves.isEmpty()) {
            Text(if (searching) "Searching saved titles…" else "No saved titles match",
                color = AppColors.Dim, style = TvType.body,
                modifier = Modifier.padding(top = 12.dp))
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(visibleShelves.size, key = { visibleShelves[it].id }) { index ->
                val shelf = visibleShelves[index]
                ShelfRow(shelf, requesters[index],
                    restoreKey = pendingFocusCardKey.takeIf { key -> shelf.cards.any { it.key == key } }
                        ?.let { ShelfRestoreKey.of(shelf.id, it) }
                        .takeIf { query.isEmpty() },
                    onRestoreConsumed = onPendingFocusConsumed,
                    onSeeAll = { onBrowse(shelf.type) },
                    onOpen = onOpen, onContextMenu = { onOpen(it) })
            }
        }
    }
}
