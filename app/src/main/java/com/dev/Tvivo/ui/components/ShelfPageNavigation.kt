package com.dev.Tvivo.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import com.dev.Tvivo.home.ShelfVerticalDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun shelfPageDirection(keyCode: Int): ShelfVerticalDirection? = when (keyCode) {
    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> ShelfVerticalDirection.Down
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> ShelfVerticalDirection.Up
    else -> null
}

/** Move about one viewport of shelf rows, then restore focus to a real row item. */
fun navigateShelfPage(
    scope: CoroutineScope,
    listState: LazyListState,
    registry: ShelfFocusRegistry,
    shelfIds: List<String>,
    rowIndexes: Map<String, Int>,
    direction: ShelfVerticalDirection
) {
    if (shelfIds.isEmpty()) return
    val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }.filter { it > 0 }
    val page = (visible.size - 1).coerceAtLeast(1)
    val current = if (direction == ShelfVerticalDirection.Down) visible.lastOrNull() ?: 1
        else visible.firstOrNull() ?: 1
    val targetIndex = if (direction == ShelfVerticalDirection.Down)
        (current + page).coerceAtMost(shelfIds.size)
    else (current - page).coerceAtLeast(1)
    val targetId = shelfIds.getOrNull(targetIndex - 1) ?: return
    val lazyIndex = rowIndexes[targetId] ?: targetIndex
    scope.launch {
        runCatching { listState.animateScrollToItem(lazyIndex) }.getOrElse { return@launch }
        repeat(5) {
            withFrameNanos {}
            if (registry.request(targetId)) return@launch
        }
    }
}
