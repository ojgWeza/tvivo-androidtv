package com.dev.Tvivo.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.tv.material3.Icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import com.dev.Tvivo.AppIdentity
import com.dev.Tvivo.R
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.repository.HomeCard
import com.dev.Tvivo.data.repository.HomeRepository
import com.dev.Tvivo.data.repository.HomeSnapshot
import com.dev.Tvivo.data.repository.HomeShelf
import com.dev.Tvivo.data.repository.PlaybackStateRepository
import com.dev.Tvivo.data.repository.roomKey
import com.dev.Tvivo.ui.browse.ItemContextMenu
import com.dev.Tvivo.ui.browse.ResumePrompt
import com.dev.Tvivo.ui.common.ConfirmDialog
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.common.screenHeadingRequester
import com.dev.Tvivo.ui.components.ShelfRow
import com.dev.Tvivo.ui.components.LandingScaffold
import com.dev.Tvivo.ui.components.landingContentPadding
import com.dev.Tvivo.ui.components.LandingLoadingSkeleton
import com.dev.Tvivo.ui.components.ShelfFocusRegistry
import com.dev.Tvivo.ui.components.navigateBetweenShelves
import com.dev.Tvivo.home.ShelfVerticalDirection
import com.dev.Tvivo.home.VirtualFolderKey
import com.dev.Tvivo.ui.shell.LocalShellFocus
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.LocalWindowSizeClass
import com.dev.Tvivo.ui.adaptive.WindowSizeClass
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class ContentType(val label: String) {
    LIVE("Live TV"), MOVIES("Movies"), SERIES("Series");

    @get:DrawableRes
    val art: Int get() = when (this) {
        LIVE -> R.drawable.tile_live
        MOVIES -> R.drawable.tile_movies
        SERIES -> R.drawable.tile_series
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    accountId: String,
    providerHost: String,
    subscriptionExpiry: String? = null,
    accountWarning: Boolean = false,
    pendingFocusCardKey: String? = null,
    lastSelected: ContentType? = null,
    isRefreshing: Boolean,
    refreshMessage: String?,
    onRefreshEverything: () -> Unit,
    onSelect: (ContentType) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCard: (HomeCard, String) -> Unit,
    onPlayCard: (HomeCard, Long, String) -> Unit,
    onSeeAll: (HomeShelf) -> Unit,
    onExit: () -> Unit,
    exitRequest: Int = 0,
    onExitRequestHandled: () -> Unit = {},
    onPendingFocusConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val repo = remember(accountId) { HomeRepository(AppDatabase.get(context), accountId) }
    val playback = remember(accountId) { PlaybackStateRepository(AppDatabase.get(context), accountId) }
    val seed = rememberSaveable(accountId) { Random.nextInt(1, Int.MAX_VALUE / 2) }
    var reload by remember { mutableIntStateOf(0) }
    var snapshot by remember(accountId) { mutableStateOf<HomeSnapshot?>(null) }
    var menuCard by remember { mutableStateOf<HomeCard?>(null) }
    var resumePromptCard by remember { mutableStateOf<Pair<HomeCard, Long>?>(null) }
    var seeAllShelf by remember { mutableStateOf<HomeShelf?>(null) }
    var lastFocusedShelfId by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    var confirmingExit by remember { mutableStateOf(false) }
    LaunchedEffect(exitRequest) {
        if (exitRequest > 0) {
            confirmingExit = true
            onExitRequestHandled()
        }
    }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(accountId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { playback.continueWatching(TYPE_VOD).collect { reload++ } }
            launch { playback.continueWatching(TYPE_SERIES).collect { reload++ } }
        }
    }
    LaunchedEffect(accountId, seed, reload, isRefreshing) {
        if (!isRefreshing) snapshot = repo.load(seed)
    }
    val shelves = snapshot?.shelves.orEmpty()
    val shellFocus = LocalShellFocus.current
    val firstNonEmptyShelfId = shelves.firstOrNull { it.cards.isNotEmpty() }?.id
    val rowRequesters = remember(shelves.map { it.id }, firstNonEmptyShelfId, shellFocus) {
        shelves.map { shelf ->
            if (shelf.id == firstNonEmptyShelfId && shellFocus != null)
                shellFocus.firstContent else FocusRequester()
        }
    }
    val listState = rememberLazyListState()
    val shelfFocusRegistry = remember { ShelfFocusRegistry() }
    val nonEmptyShelfIds = shelves.filter { it.cards.isNotEmpty() }.map { it.id }
    val shelfRowIndexes = shelves.mapIndexed { index, shelf -> shelf.id to index + 1 }.toMap()
    val screenHeadingRequester = remember { BringIntoViewRequester() }
    val tileRequester = shellFocus?.firstContent ?: remember { FocusRequester() }
    val phone = !LocalIsTelevision.current &&
        LocalWindowSizeClass.current == WindowSizeClass.Compact
    val latestRefresh by rememberUpdatedState(onRefreshEverything)
    val latestRefreshing by rememberUpdatedState(isRefreshing)
    val pullThreshold = with(LocalDensity.current) { 80.dp.toPx() }
    val pull = remember(phone, listState, pullThreshold) {
        object : NestedScrollConnection {
            private var distance = 0f
            override fun onPostScroll(consumed: Offset, available: Offset,
                                      source: NestedScrollSource): Offset {
                if (phone && listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0 && available.y > 0f)
                    distance += available.y
                else if (available.y < 0f) distance = 0f
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (distance >= pullThreshold && !latestRefreshing) latestRefresh()
                distance = 0f
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(snapshot?.isFirstRun, lastSelected) {
        if (snapshot?.isFirstRun == true) {
            androidx.compose.runtime.withFrameNanos {}
            runCatching { tileRequester.requestFocus() }
        }
    }
    LaunchedEffect(pendingFocusCardKey) {
        val restoreKey = pendingFocusCardKey ?: return@LaunchedEffect
        val shelfId = restoreKey.substringBefore('|')
        val cardKey = restoreKey.substringAfter('|', missingDelimiterValue = "")
        val loadedShelves = androidx.compose.runtime.snapshotFlow { snapshot?.shelves }
            .filterNotNull().first()
        val index = loadedShelves.indexOfFirst { shelf ->
            shelf.id == shelfId && shelf.cards.any { it.key == cardKey }
        }
        if (index >= 0) listState.scrollToItem(index + 1)
        else onPendingFocusConsumed()
    }

    LandingScaffold(
        state = listState,
        modifier = Modifier.nestedScroll(pull),
        contentPadding = landingContentPadding()
    ) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().screenHeadingRequester(screenHeadingRequester)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!LocalIsTelevision.current) Text(AppIdentity.personalLibrary(),
                        color = AppColors.Ink, style = TvType.headline)
                    Spacer(Modifier.weight(1f))
                    if (phone) Icon(Icons.Default.Settings, contentDescription = "Settings",
                        tint = AppColors.Ink,
                        modifier = Modifier.testTag("home-settings")
                            .heightIn(min = 48.dp).widthIn(min = 48.dp)
                            .tvFocusFrame().tvClickable(onClick = onOpenSettings)
                            .padding(10.dp))
                }
                if (refreshMessage != null) {
                    val failed = refreshMessage.contains("fail", true) ||
                        refreshMessage.contains("error", true) || refreshMessage.contains("could not", true)
                    Text(if (failed) "Couldn't refresh. Showing saved titles" else refreshMessage,
                        color = AppColors.Dim, style = TvType.label)
                    if (failed && !isRefreshing) HeaderAction("Retry", onRefreshEverything)
                }
            }
        }
        if (snapshot == null) {
            item(key = "loading") { LandingLoadingSkeleton() }
        } else if (snapshot?.isFirstRun == true) {
            item(key = "first-run") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Your library is ready to explore", color = AppColors.Ink, style = TvType.title)
                    BoxWithConstraints {
                        if (maxWidth < 600.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf(ContentType.MOVIES, ContentType.SERIES, ContentType.LIVE).forEach {
                                    RouteTile(it, Modifier.fillMaxWidth().then(
                                        if (it == (lastSelected ?: ContentType.LIVE))
                                            Modifier.focusRequester(tileRequester) else Modifier), onSelect)
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf(ContentType.MOVIES, ContentType.SERIES, ContentType.LIVE).forEach {
                                    RouteTile(it, Modifier.weight(1f).then(
                                        if (it == (lastSelected ?: ContentType.LIVE))
                                            Modifier.focusRequester(tileRequester) else Modifier), onSelect)
                                }
                            }
                        }
                    }
                    HeaderAction("Refresh", onRefreshEverything)
                }
            }
        } else {
            items(shelves.size, key = { shelves[it].id }) { index ->
                val shelf = shelves[index]
                ShelfRow(shelf, rowRequesters[index],
                    aboveRequester = shellFocus?.topBar.takeIf { shelf.id == firstNonEmptyShelfId },
                    restoreKey = pendingFocusCardKey.takeIf { it?.substringBefore('|') == shelf.id },
                    onRestoreConsumed = onPendingFocusConsumed,
                    screenHeadingRequester = screenHeadingRequester.takeIf {
                        shelf.id == firstNonEmptyShelfId
                    },
                    focusRegistry = shelfFocusRegistry,
                    onVerticalNavigate = { direction ->
                        navigateBetweenShelves(scope, listState, shelfFocusRegistry,
                            nonEmptyShelfIds, shelfRowIndexes, shelf.id, direction)
                    },
                    onSeeAll = {
                        lastFocusedShelfId = shelf.id
                        if (shelf.typeCounts.isNotEmpty()) seeAllShelf = shelf else onSeeAll(shelf)
                    },
                    onOpen = { card -> lastFocusedShelfId = shelf.id; onOpenCard(card, shelf.id) },
                    onContextMenu = { card -> lastFocusedShelfId = shelf.id; menuCard = card })
            }
        }
    }

    menuCard?.let { card ->
        val key = card.type.roomKey()
        var favourite by remember(card.key) { mutableStateOf(false) }
        var resumeMs by remember(card.key) { mutableStateOf(0L) }
        LaunchedEffect(card.key) {
            playback.isFavourite(key, card.item.id.toString()).collect { favourite = it }
        }
        LaunchedEffect(card.key) {
            resumeMs = playback.resumePosition(key, card.resumeItemId ?: card.item.id.toString())
                ?.positionMs ?: 0L
        }
        ItemContextMenu(card.item, favourite, resumeMs > 0,
            onDismiss = { menuCard = null },
            onPlay = {
                menuCard = null
                if (card.type == ContentType.MOVIES && resumeMs > 0L)
                    resumePromptCard = card to resumeMs
                else onPlayCard(card, 0L, lastFocusedShelfId.orEmpty())
            },
            onToggleFavourite = {
                menuCard = null
                scope.launch {
                    playback.toggleFavourite(key, card.item.id.toString(), !favourite)
                    reload++
                }
            },
            onClearResume = {
                menuCard = null
                scope.launch {
                    playback.clearResume(key, card.resumeItemId ?: card.item.id.toString())
                    reload++
                }
            })
    }
    resumePromptCard?.let { (card, positionMs) ->
        ResumePrompt(
            title = card.item.title,
            positionMs = positionMs,
            onResume = { resumePromptCard = null; onPlayCard(card, positionMs, lastFocusedShelfId.orEmpty()) },
            onStartOver = { resumePromptCard = null; onPlayCard(card, 0L, lastFocusedShelfId.orEmpty()) },
            onDismiss = { resumePromptCard = null }
        )
    }
    seeAllShelf?.let { shelf ->
        val choiceFocus = remember(shelf.id) { FocusRequester() }
        LaunchedEffect(shelf.id) {
            repeat(5) {
                androidx.compose.runtime.withFrameNanos {}
                if (runCatching { choiceFocus.requestFocus() }.isSuccess)
                    return@LaunchedEffect
            }
        }
        Dialog(onDismissRequest = { seeAllShelf = null }) {
            Column(Modifier.background(AppColors.Elevated).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("See all ${shelf.title.lowercase()}", color = AppColors.Ink,
                    style = TvType.title)
                shelf.typeCounts.filterValues { it > 0 }.keys.forEachIndexed { index, type ->
                    HeaderAction(type.label, onClick = {
                        seeAllShelf = null
                        val prefix = when (shelf.destination) {
                            VirtualFolderKey.ContinueWatching -> "__my_continue_"
                            VirtualFolderKey.RecentlyAdded -> "__my_added_"
                            VirtualFolderKey.RecentlyPlayed -> "__my_played_"
                            VirtualFolderKey.Favorites -> "__my_favorites_"
                            null -> return@HeaderAction
                        }
                        onSeeAll(shelf.copy(id = prefix + type.roomKey(), type = type,
                            typeCounts = emptyMap()))
                    }, modifier = if (index == 0) Modifier.focusRequester(choiceFocus) else Modifier)
                }
                HeaderAction("Cancel", onClick = { seeAllShelf = null })
            }
        }
    }
    if (confirmingExit) ConfirmDialog(
        title = "Exit ${AppIdentity.name()}?",
        consequence = "Closes the app. Your account and saved titles stay here.",
        confirmLabel = "Exit", safeLabel = "Stay in ${AppIdentity.name()}",
        onConfirm = { confirmingExit = false; onExit() },
        onDismiss = { confirmingExit = false }
    )
}

@Composable
private fun HeaderAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(label, color = AppColors.Ink, style = TvType.label,
        modifier = modifier.heightIn(min = 48.dp).tvFocusFrame().tvClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp))
}

@Composable
private fun RouteTile(type: ContentType, modifier: Modifier, onSelect: (ContentType) -> Unit) {
    Box(modifier.height(150.dp).tvFocusFrame().tvClickable { onSelect(type) }) {
        Image(painterResource(type.art), null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Transparent, 1f to AppColors.MediaBase.copy(alpha = 0.95f))))
        Text(type.label, color = AppColors.MediaText, style = TvType.headline,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
    }
}
