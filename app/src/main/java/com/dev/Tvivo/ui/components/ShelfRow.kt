package com.dev.Tvivo.ui.components

import android.view.KeyEvent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.dev.Tvivo.data.repository.HomeCard
import com.dev.Tvivo.data.repository.HomeShelf
import com.dev.Tvivo.home.ShelfRestoreKey
import com.dev.Tvivo.home.ShelfVerticalDirection
import com.dev.Tvivo.home.LandingShelfPolicy
import com.dev.Tvivo.home.ShelfDecision
import com.dev.Tvivo.home.ShelfLoadState
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.common.rememberShelfHeaderVisibility
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfRow(
    shelf: HomeShelf,
    rowRequester: FocusRequester,
    aboveRequester: FocusRequester? = null,
    restoreKey: String? = null,
    onRestoreConsumed: () -> Unit = {},
    screenHeadingRequester: androidx.compose.foundation.relocation.BringIntoViewRequester? = null,
    onSeeAll: () -> Unit,
    onOpen: (HomeCard) -> Unit,
    onContextMenu: (HomeCard) -> Unit,
    onLeadingLeft: (() -> Unit)? = null,
    seeAllRequester: FocusRequester? = null,
    focusRegistry: ShelfFocusRegistry? = null,
    onVerticalNavigate: ((ShelfVerticalDirection) -> Boolean)? = null,
    loadState: ShelfLoadState = ShelfLoadState.Loaded
) {
    val decision = LandingShelfPolicy.decide(false, shelf.core, loadState, shelf.cards.size,
        shelf.totalCount, shelf.hasSeeAllDestination, shelf.emptyRule)
    if (decision == ShelfDecision.Hidden) return
    if (decision == ShelfDecision.Loading) {
        LandingLoadingSkeleton(channel = shelf.type == ContentType.LIVE)
        return
    }
    val state = rememberLazyListState()
    var lastFocusedKey by rememberSaveable(shelf.id) { mutableStateOf<String?>(null) }
    val itemRequester = remember { FocusRequester() }
    val headerVisibility = rememberShelfHeaderVisibility(screenHeadingRequester)
    val scope = rememberCoroutineScope()
    LaunchedEffect(restoreKey) {
        val index = shelf.cards.indexOfFirst { ShelfRestoreKey.of(shelf.id, it.key) == restoreKey }
        if (index < 0) {
            if (restoreKey != null) onRestoreConsumed()
            return@LaunchedEffect
        }
        lastFocusedKey = shelf.cards[index].key
        state.scrollToItem(index)
        repeat(5) {
            withFrameNanos {}
            if (runCatching { itemRequester.requestFocus() }.isSuccess)
            {
                onRestoreConsumed()
                return@LaunchedEffect
            }
        }
        onRestoreConsumed()
    }
    DisposableEffect(shelf.id, shelf.cards, focusRegistry) {
        if (focusRegistry != null) focusRegistry.register(shelf.id) {
            val targetKey = lastFocusedKey ?: shelf.cards.firstOrNull()?.key ?: return@register false
            val targetIndex = shelf.cards.indexOfFirst { it.key == targetKey }
            if (targetIndex < 0) return@register false
            lastFocusedKey = targetKey
            state.scrollToItem(targetIndex)
            repeat(5) {
                withFrameNanos {}
                if (runCatching { itemRequester.requestFocus() }.isSuccess) return@register true
            }
            false
        }
        onDispose { focusRegistry?.unregister(shelf.id) }
    }
    fun targetVisible(): Boolean = runCatching {
        state.layoutInfo.visibleItemsInfo.any { info ->
            shelf.cards.getOrNull(info.index)?.key == lastFocusedKey
        }
    }.getOrDefault(false)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(shelf.title, color = AppColors.Ink, style = TvType.title,
                modifier = Modifier.weight(1f).then(headerVisibility.shelfHeadingModifier()))
            if ((decision as? ShelfDecision.Cards)?.showSeeAll == true) Box(
                contentAlignment = androidx.compose.ui.Alignment.Center,
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                    .semantics { contentDescription = "See all ${shelf.title}" }
                    .testTag("see-all-${shelf.id}")
                    .then(if (seeAllRequester != null) Modifier.focusRequester(seeAllRequester) else Modifier)
                    .tvFocusFrame().tvClickable(onClick = onSeeAll)
                    .then(if (onLeadingLeft != null) Modifier.onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action == KeyEvent.ACTION_DOWN &&
                            native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            onLeadingLeft()
                            true
                        } else false
                    } else Modifier)
                    .padding(horizontal = 8.dp)
            ) {
                Text("See all", color = AppColors.Dim, style = TvType.label)
            }
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
                .focusRequester(rowRequester)
                .focusProperties {
                    enter = {
                        runCatching {
                            if (targetVisible()) itemRequester else FocusRequester.Default
                        }.getOrDefault(FocusRequester.Default)
                    }
                }
                .then(if (onVerticalNavigate != null) Modifier.onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> onVerticalNavigate(ShelfVerticalDirection.Up)
                        KeyEvent.KEYCODE_DPAD_DOWN -> onVerticalNavigate(ShelfVerticalDirection.Down)
                        else -> false
                    }
                } else Modifier)
                .focusGroup()
        ) {
            if (decision == ShelfDecision.Hint) item(key = "empty") {
                Text(shelf.emptyHint ?: "Nothing here yet", color = AppColors.Dim,
                    style = TvType.label,
                    modifier = Modifier.widthIn(min = 110.dp)
                        .heightIn(min = if (shelf.type == ContentType.LIVE) 62.dp else 165.dp)
                        .testTag("shelf-empty-${shelf.id}")
                        .focusRequester(itemRequester)
                        .focusProperties { aboveRequester?.let { up = it } }
                        .onFocusChanged {
                            if (it.isFocused) scope.launch { headerVisibility.reveal() }
                        }
                        .tvFocusFrame().focusable()
                        .padding(horizontal = 12.dp, vertical = 16.dp))
            }
            itemsIndexed(shelf.cards, key = { _, card -> card.key }) { index, card ->
                ShelfCard(
                    card = card,
                    modifier = Modifier
                        .then(if (card.key == lastFocusedKey) Modifier.focusRequester(itemRequester) else Modifier)
                        .onFocusChanged {
                            if (it.isFocused) {
                                lastFocusedKey = card.key
                                scope.launch { headerVisibility.reveal() }
                            }
                        }
                        .focusProperties { aboveRequester?.let { up = it } }
                        .then(if (onLeadingLeft != null && index == state.firstVisibleItemIndex)
                            Modifier.onPreviewKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                if (native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                                    native.action == KeyEvent.ACTION_DOWN) {
                                    onLeadingLeft()
                                    true
                                } else false
                            } else Modifier),
                    onClick = { onOpen(card) },
                    onContextMenu = { onContextMenu(card) }
                )
            }
        }
    }
}
