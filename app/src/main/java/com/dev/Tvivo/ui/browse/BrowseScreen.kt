package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.ErrorCopy
import com.dev.Tvivo.ui.common.IconPill
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun BrowseScreen(
    contentType: ContentType,
    onPlay: (BrowseItem, Long) -> Unit
) {
    val application = LocalContext.current.applicationContext as android.app.Application
    // Keyed by content type: Live and Movies are separate screens with separate
    // categories, selection and focus, and must not share one instance.
    val viewModel: BrowseViewModel = viewModel(
        key = "browse:" + contentType.name,
        factory = viewModelFactory {
            initializer {
                BrowseViewModel(application, createSavedStateHandle(), contentType)
            }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    var contextMenuItem by remember { mutableStateOf<BrowseItem?>(null) }
    var resumePromptItem by remember { mutableStateOf<Pair<BrowseItem, Long>?>(null) }
    val scope = rememberCoroutineScope()
    val gridFocusRequester = remember { FocusRequester() }

    // OK plays. If a resume point exists the prompt comes first, so neither resuming nor
    // starting over happens silently.
    fun launch(item: BrowseItem) {
        scope.launch {
            val resumeMs = viewModel.resumePositionMs(item.id)
            viewModel.pendingFocusItemId = item.id
            if (resumeMs != null && resumeMs > 0) {
                resumePromptItem = item to resumeMs
            } else {
                onPlay(item, 0L)
            }
        }
    }

    LaunchedEffect(state.refreshConfirmation) {
        if (state.refreshConfirmation != null) {
            delay(2000)
            viewModel.dismissRefreshConfirmation()
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(Palette.Bg)) {
        CategoryRail(
            categories = state.categories,
            counts = state.counts,
            selectedCategoryId = state.selectedCategoryId,
            onSelect = { viewModel.selectCategory(it.categoryId) },
            gridFocusRequester = gridFocusRequester
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                title = state.categories
                    .firstOrNull { it.categoryId == state.selectedCategoryId }
                    ?.name
                    .orEmpty(),
                isRefreshing = state.isRefreshing,
                confirmation = state.refreshConfirmation,
                syncState = state.catalogSyncState,
                syncDone = state.catalogSyncDone,
                onRefresh = viewModel::refreshSelected
            )

            when {
                // Offline is fully browsable: a dead network must fail at play time, not
                // throw a full-screen error over data the app already has.
                state.error != null && items.itemCount == 0 -> ErrorState(state.error!!)

                items.itemCount == 0 &&
                    items.loadState.refresh !is androidx.paging.LoadState.Loading ->
                    EmptyState(onRefresh = viewModel::refreshSelected)

                else -> ContentGrid(
                    items = items,
                    pendingFocusItemId = viewModel.pendingFocusItemId,
                    cardShape = viewModel.cardShape,
                    gridFocusRequester = gridFocusRequester,
                    onPlay = { item -> launch(item) },
                    onContextMenu = { contextMenuItem = it },
                    onFocused = { viewModel.pendingFocusItemId = it.id }
                )
            }
        }
    }

    contextMenuItem?.let { item ->
        val isFavourite by (viewModel.isFavourite(item.id) ?: flowOf(false))
            .collectAsStateWithLifecycle(initialValue = false)
        var hasResume by remember(item.id) { mutableStateOf(false) }
        LaunchedEffect(item.id) {
            hasResume = (viewModel.resumePositionMs(item.id) ?: 0L) > 0
        }

        ItemContextMenu(
            item = item,
            isFavourite = isFavourite,
            hasResumePoint = hasResume,
            onDismiss = { contextMenuItem = null },
            onPlay = {
                contextMenuItem = null
                launch(item)
            },
            onToggleFavourite = {
                viewModel.toggleFavourite(item.id, !isFavourite)
                contextMenuItem = null
            },
            onClearResume = {
                viewModel.clearResume(item.id)
                contextMenuItem = null
            }
        )
    }

    resumePromptItem?.let { (item, positionMs) ->
        ResumePrompt(
            title = item.title,
            positionMs = positionMs,
            onResume = {
                resumePromptItem = null
                onPlay(item, positionMs)
            },
            onStartOver = {
                resumePromptItem = null
                onPlay(item, 0L)
            },
            onDismiss = { resumePromptItem = null }
        )
    }
}

@Composable
private fun Header(
    title: String,
    isRefreshing: Boolean,
    confirmation: String?,
    syncState: String?,
    syncDone: Int,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The header shows the category name immediately, before any row arrives.
            Text(text = title, color = Palette.Ink, style = TvType.headline)

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Visible progress that dismisses at 100%. The rail counts filling in
                // category by category are the secondary signal.
                if (syncState == "indexing") {
                    Text(
                        text = "Indexing $syncDone…",
                        color = Palette.Dim,
                        style = TvType.label,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
                confirmation?.let {
                    Text(text = it, color = Palette.Dim, style = TvType.label,
                        modifier = Modifier.padding(end = 16.dp))
                }
                // D-8 — the same component Home uses. This was a bare text link, which
                // made one global action look like two different things depending on
                // which screen you met it on. Reached by pressing UP from the grid's top
                // row.
                //
                // Note the scope difference the label has to carry: this refreshes the
                // *current category*, Home's pill refreshes everything.
                IconPill(
                    glyph = "↻",
                    label = "Refresh this category",
                    enabled = !isRefreshing,
                    onClick = onRefresh
                )
            }
        }

        // Cache present and refreshing: a 3 px line under the header, never a blocking
        // spinner over content the user can already read.
        if (isRefreshing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(3.dp)
                    .background(Palette.Accent)
            )
        }
    }
}

@Composable
private fun ErrorState(error: com.dev.Tvivo.data.AppError) {
    val copy = ErrorCopy.of(error)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = copy.message, color = Palette.Ink, style = TvType.title)
            copy.primaryAction?.let {
                Text(
                    text = it,
                    color = Palette.AccentText,
                    style = TvType.body,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onRefresh: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Nothing in this category", color = Palette.Ink, style = TvType.title)
            Text(
                text = "Refresh",
                color = Palette.AccentText,
                style = TvType.body,
                modifier = Modifier.clickable { onRefresh() }.padding(top = 16.dp)
            )
        }
    }
}
