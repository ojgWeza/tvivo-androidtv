package com.dev.Tvivo.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
import com.dev.Tvivo.home.FocusLinkPolicy
import com.dev.Tvivo.home.ShelfNavigationPolicy
import com.dev.Tvivo.home.ShelfVerticalDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Rows register only while composed; a request is retried after scrolling composes its target. */
class ShelfFocusRegistry {
    private val actions = mutableMapOf<String, suspend () -> Boolean>()

    fun register(shelfId: String, action: suspend () -> Boolean) {
        actions[shelfId] = action
    }

    fun unregister(shelfId: String) {
        actions.remove(shelfId)
    }

    suspend fun request(shelfId: String): Boolean =
        runCatching { actions[shelfId]?.invoke() ?: false }.getOrDefault(false)
}

/** Shared Up/Down policy and scroll/compose/focus sequence for Home and type landings. */
fun navigateBetweenShelves(
    scope: CoroutineScope,
    listState: LazyListState,
    registry: ShelfFocusRegistry,
    shelfIds: List<String>,
    rowIndexes: Map<String, Int>,
    currentShelfId: String,
    direction: ShelfVerticalDirection,
    headerActionRequester: FocusRequester? = null
): Boolean {
    val targetId = ShelfNavigationPolicy.adjacentNonEmptyShelf(shelfIds, currentShelfId, direction)
    if (targetId == null) {
        if (direction != ShelfVerticalDirection.Up || headerActionRequester == null) return false
        scope.launch {
            if (runCatching { listState.animateScrollToItem(0) }.isFailure) return@launch
            var focused = false
            repeat(5) {
                withFrameNanos {}
                val headerIsComposed = listState.layoutInfo.visibleItemsInfo.any { it.index == 0 }
                val target = FocusLinkPolicy.targetIfRendered(headerActionRequester, headerIsComposed)
                if (!focused && target != null) {
                    focused = runCatching { target.requestFocus() }.isSuccess
                }
            }
        }
        return true
    }
    val targetIndex = rowIndexes[targetId] ?: return false
    scope.launch {
        // Keeping index zero visible also keeps the screen heading visible when returning
        // to the first shelf. Later shelves align by their heading row.
        if (runCatching {
                listState.animateScrollToItem(if (targetIndex == 1) 0 else targetIndex)
            }.isFailure) return@launch
        repeat(5) {
            withFrameNanos {}
            if (registry.request(targetId)) return@launch
        }
    }
    return true
}
