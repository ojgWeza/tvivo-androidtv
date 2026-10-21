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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
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
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.televisionDensity
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
    loadState: ShelfLoadState = ShelfLoadState.Loaded,
    focusIdentity: String = shelf.id,
    onPageNavigate: ((ShelfVerticalDirection) -> Unit)? = null
) {
    val decision = LandingShelfPolicy.decide(false, shelf.core, loadState, shelf.cards.size,
        shelf.totalCount, shelf.hasSeeAllDestination, shelf.emptyRule)
    if (decision == ShelfDecision.Hidden) return
    if (decision == ShelfDecision.Loading) {
        LandingLoadingSkeleton(channel = shelf.type == ContentType.LIVE)
        return
    }
    val state = rememberLazyListState()
    var lastFocusedKey by rememberSaveable(focusIdentity) { mutableStateOf<String?>(null) }
    var focusedCardKey by remember(focusIdentity) { mutableStateOf<String?>(null) }
    val itemRequester = remember { FocusRequester() }
    val headerRequester = seeAllRequester ?: remember { FocusRequester() }
    val headerVisibility = rememberShelfHeaderVisibility(screenHeadingRequester)
    val scope = rememberCoroutineScope()
    val television = LocalIsTelevision.current
    val largeText = LocalConfiguration.current.fontScale >= 1.6f
    val tvDensity = if (television) televisionDensity() else null
    val shelfTitle = when (shelf.destination) {
        com.dev.Tvivo.home.VirtualFolderKey.ContinueWatching -> stringResource(com.dev.Tvivo.R.string.shelf_continue_watching)
        com.dev.Tvivo.home.VirtualFolderKey.RecentlyAdded -> stringResource(when (shelf.id) {
            "__my_added_movie" -> com.dev.Tvivo.R.string.shelf_recently_added_movies
            "__my_added_series" -> com.dev.Tvivo.R.string.shelf_recently_added_series
            "__my_added_live" -> com.dev.Tvivo.R.string.shelf_recently_added_live
            else -> com.dev.Tvivo.R.string.shelf_recently_added
        })
        com.dev.Tvivo.home.VirtualFolderKey.RecentlyPlayed -> stringResource(com.dev.Tvivo.R.string.shelf_last_watched)
        com.dev.Tvivo.home.VirtualFolderKey.Favorites -> stringResource(com.dev.Tvivo.R.string.shelf_favourites)
        null -> when {
            shelf.id == "__my_suggestions" -> stringResource(com.dev.Tvivo.R.string.shelf_suggestions)
            shelf.title.equals("ALL", ignoreCase = true) -> stringResource(com.dev.Tvivo.R.string.folder_all)
            else -> shelf.title
        }
    }
    val openAllLabel = stringResource(com.dev.Tvivo.R.string.shelf_open_all, shelfTitle)
    val seeAllLabel = stringResource(com.dev.Tvivo.R.string.shelf_see_all, shelfTitle)
    val showSeeAll = (decision as? ShelfDecision.Cards)?.showSeeAll == true
    val focusTargetKey = lastFocusedKey ?: shelf.cards.firstOrNull()?.key
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
    DisposableEffect(shelf.id, focusIdentity, shelf.cards, focusRegistry, television, showSeeAll) {
        if (focusRegistry != null) focusRegistry.register(focusIdentity) {
            val targetIndex = shelf.cards.indexOfFirst { it.key == lastFocusedKey }
                .takeIf { it >= 0 } ?: shelf.cards.indices.firstOrNull() ?: return@register false
            val targetKey = shelf.cards[targetIndex].key
            lastFocusedKey = targetKey
            state.scrollToItem(targetIndex)
            repeat(60) {
                withFrameNanos {}
                runCatching { itemRequester.requestFocus() }
                if (focusedCardKey == targetKey) return@register true
            }
            false
        }
        if (focusRegistry != null && television && showSeeAll) focusRegistry.registerHeader(focusIdentity) {
            repeat(5) {
                withFrameNanos {}
                if (runCatching { headerRequester.requestFocus() }.isSuccess) return@registerHeader true
            }
            false
        }
        onDispose { focusRegistry?.unregister(focusIdentity) }
    }
    fun targetVisible(): Boolean = runCatching {
        state.layoutInfo.visibleItemsInfo.any { info ->
            shelf.cards.getOrNull(info.index)?.key == lastFocusedKey
        }
    }.getOrDefault(false)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = if (television) (tvDensity!!.sectionGap / 2).dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (television) (tvDensity!!.cardGap / 2).dp else 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (television && showSeeAll) {
                Row(Modifier.fillMaxWidth().heightIn(min = if (television) tvDensity!!.headerHeight.dp else 48.dp)
                    .then(headerVisibility.shelfHeadingModifier())
                    .focusRequester(headerRequester)
                    .focusProperties { aboveRequester?.let { up = it } }
                    .semantics { contentDescription = openAllLabel }
                    .testTag("see-all-${shelf.id}")
                    .onFocusChanged {
                        if (it.isFocused) scope.launch { headerVisibility.revealShelf() }
                    }
                    .tvFocusFrame().tvClickable(onClick = onSeeAll)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        shelfPageDirection(native.keyCode)?.let { direction ->
                            if (television && onPageNavigate != null && native.repeatCount == 0)
                                onPageNavigate(direction)
                            return@onPreviewKeyEvent television && onPageNavigate != null
                        }
                        when (native.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> onVerticalNavigate?.invoke(ShelfVerticalDirection.Up) == true
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val targetIndex = shelf.cards.indexOfFirst { it.key == focusTargetKey }
                                    .takeIf { it >= 0 } ?: 0
                                shelf.cards.getOrNull(targetIndex)?.let { lastFocusedKey = it.key }
                                scope.launch {
                                    state.animateScrollToItem(targetIndex)
                                    repeat(5) {
                                        withFrameNanos {}
                                        if (runCatching { itemRequester.requestFocus() }.isSuccess) return@launch
                                    }
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                onLeadingLeft?.invoke()
                                onLeadingLeft != null
                            }
                            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_UP,
                            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_CHANNEL_UP ->
                                television && onPageNavigate != null
                            else -> false
                        }
                    }
                    .padding(horizontal = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(shelfTitle, color = AppColors.Ink, style = TvType.title,
                        modifier = Modifier.weight(1f))
                    Text("›", color = AppColors.Ink, style = TvType.title)
                    if (!largeText) Text(stringResource(com.dev.Tvivo.R.string.search_see_all_titles),
                        color = AppColors.Dim, style = TvType.label)
                }
            } else {
                Text(shelfTitle, color = AppColors.Ink, style = TvType.title,
                    modifier = Modifier.weight(1f).then(headerVisibility.shelfHeadingModifier()))
                if (showSeeAll) Box(
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                    modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                        .semantics { contentDescription = seeAllLabel }
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
                    Text(if (largeText) "›" else stringResource(com.dev.Tvivo.R.string.search_see_all_titles),
                        color = AppColors.Dim, style = TvType.label)
                }
            }
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (television) 8.dp else 10.dp),
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
                        KeyEvent.KEYCODE_DPAD_UP -> if (television && showSeeAll)
                            runCatching { headerRequester.requestFocus() }.isSuccess
                        else onVerticalNavigate(ShelfVerticalDirection.Up)
                        KeyEvent.KEYCODE_DPAD_DOWN -> onVerticalNavigate(ShelfVerticalDirection.Down)
                        else -> false
                    }
                } else Modifier)
                .focusGroup()
        ) {
            if (decision == ShelfDecision.Hint) item(key = "shelf:${shelf.id}:empty") {
                Text(shelf.emptyHint ?: "Nothing here yet", color = AppColors.Dim,
                    style = TvType.label,
                    modifier = Modifier.widthIn(min = 110.dp)
                        .heightIn(min = if (shelf.type == ContentType.LIVE) 62.dp else 165.dp)
                        .testTag("shelf-empty-${shelf.id}")
                        .focusRequester(itemRequester)
                        .focusProperties {
                            if (!television || !showSeeAll) aboveRequester?.let { up = it }
                        }
                        .onFocusChanged {
                            if (it.isFocused) scope.launch { headerVisibility.revealShelf() }
                        }
                        .tvFocusFrame().focusable()
                        .padding(horizontal = 12.dp, vertical = 16.dp))
            }
            itemsIndexed(shelf.cards, key = { _, card -> "shelf:${shelf.id}:card:${card.key}" }) { index, card ->
                ShelfCard(
                    card = card,
                    modifier = Modifier
                        .then(if (card.key == focusTargetKey) Modifier.focusRequester(itemRequester) else Modifier)
                        .onFocusChanged {
                            if (it.isFocused) {
                                lastFocusedKey = card.key
                                focusedCardKey = card.key
                                scope.launch { headerVisibility.revealShelf() }
                            } else if (focusedCardKey == card.key) focusedCardKey = null
                        }
                        .focusProperties {
                            if (!television || !showSeeAll) aboveRequester?.let { up = it }
                        }
                        .then(if (onLeadingLeft != null && index == state.firstVisibleItemIndex)
                            Modifier.onPreviewKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                if (native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                                    native.action == KeyEvent.ACTION_DOWN) {
                                    onLeadingLeft()
                                    true
                                } else false
                            } else Modifier)
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            val direction = shelfPageDirection(native.keyCode)
                            if (native.action == KeyEvent.ACTION_DOWN && direction != null && television && onPageNavigate != null) {
                                if (native.repeatCount == 0) onPageNavigate(direction)
                                true
                            } else false
                        },
                    onClick = { onOpen(card) },
                    onContextMenu = { onContextMenu(card) }
                )
            }
        }
    }
}
