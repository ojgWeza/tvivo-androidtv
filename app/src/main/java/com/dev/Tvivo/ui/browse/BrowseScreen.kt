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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.Text
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import com.dev.Tvivo.ui.common.ErrorCopy
import com.dev.Tvivo.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun BrowseScreen(
    onPlay: (VodStreamEntity) -> Unit,
    viewModel: BrowseViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    var contextMenuItem by remember { mutableStateOf<VodStreamEntity?>(null) }

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
            onSelect = { viewModel.selectCategory(it.categoryId) }
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                title = state.categories
                    .firstOrNull { it.categoryId == state.selectedCategoryId }
                    ?.name
                    .orEmpty(),
                isRefreshing = state.isRefreshing,
                confirmation = state.refreshConfirmation,
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
                    pendingFocusStreamId = viewModel.pendingFocusStreamId,
                    onPlay = { item ->
                        viewModel.pendingFocusStreamId = item.streamId
                        onPlay(item)
                    },
                    onContextMenu = { contextMenuItem = it },
                    onFocused = { viewModel.pendingFocusStreamId = it.streamId }
                )
            }
        }
    }

    contextMenuItem?.let { item ->
        ItemContextMenu(
            item = item,
            onDismiss = { contextMenuItem = null },
            onPlay = {
                contextMenuItem = null
                viewModel.pendingFocusStreamId = item.streamId
                onPlay(item)
            }
        )
    }
}

@Composable
private fun Header(
    title: String,
    isRefreshing: Boolean,
    confirmation: String?,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The header shows the category name immediately, before any row arrives.
            Text(text = title, color = Palette.Ink, fontSize = 24.sp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                confirmation?.let {
                    Text(text = it, color = Palette.Dim, fontSize = 14.sp,
                        modifier = Modifier.padding(end = 16.dp))
                }
                Text(
                    text = "Refresh",
                    color = Palette.AccentText,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable { onRefresh() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
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
            Text(text = copy.message, color = Palette.Ink, fontSize = 20.sp)
            copy.primaryAction?.let {
                Text(
                    text = it,
                    color = Palette.AccentText,
                    fontSize = 16.sp,
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
            Text(text = "Nothing in this category", color = Palette.Ink, fontSize = 20.sp)
            Text(
                text = "Refresh",
                color = Palette.AccentText,
                fontSize = 16.sp,
                modifier = Modifier.clickable { onRefresh() }.padding(top = 16.dp)
            )
        }
    }
}
