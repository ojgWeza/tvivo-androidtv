package com.dev.Tvivo.ui.series

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Text
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import com.dev.Tvivo.data.remote.EpisodeTitlePolicy
import com.dev.Tvivo.playback.*
import com.dev.Tvivo.ui.adaptive.LocalWindowSizeClass
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.WindowSizeClass
import com.dev.Tvivo.ui.browse.ResumePrompt
import com.dev.Tvivo.ui.common.ErrorCopy
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.detail.DetailHero
import com.dev.Tvivo.ui.detail.DetailTextButton
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.launch

/** One page from a series card to an episode. Back is owned by MainActivity. */
@Composable
fun SeriesDetailScreen(seriesId: Int, onPlayEpisode: (EpisodeEntity, Long, List<EpisodeEntity>) -> Unit,
    insideShell: Boolean = false) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val vm: SeriesDetailViewModel = viewModel(key = "series:$seriesId", factory = viewModelFactory {
        initializer { SeriesDetailViewModel(application, createSavedStateHandle(), seriesId) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val primaryFocus = remember { FocusRequester() }
    var initialFocusDone by remember(seriesId) { mutableStateOf(false) }
    var resumePrompt by remember { mutableStateOf<Pair<EpisodeEntity, Long>?>(null) }
    var menuEpisode by remember { mutableStateOf<EpisodeEntity?>(null) }
    val compact = LocalWindowSizeClass.current == WindowSizeClass.Compact

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshPlayback()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    fun play(episode: EpisodeEntity, offerPrompt: Boolean) {
        vm.pendingFocusEpisodeId = episode.episodeId
        scope.launch {
            val position = vm.resumePositionMs(episode.episodeId) ?: 0L
            if (offerPrompt && position > 0L) resumePrompt = episode to position
            else onPlayEpisode(episode, if (offerPrompt) 0L else position, vm.playbackQueue())
        }
    }

    fun playAction(action: DetailAction) {
        vm.playbackQueue().firstOrNull { it.episodeId == action.episodeId }?.let { play(it, false) }
    }

    LaunchedEffect(state.isLoading, state.actions.primary) {
        if (!initialFocusDone && !state.isLoading && state.actions.primary != null) {
            repeat(3) {
                withFrameNanos { }
                if (runCatching { primaryFocus.requestFocus() }.isSuccess) {
                    initialFocusDone = true
                    return@LaunchedEffect
                }
            }
        }
    }

    val hero: @Composable () -> Unit = {
        DetailHero(state.title, state.coverUrl, state.plot, state.quality, state.year, state.rating,
            genre = state.genre, cast = state.cast,
            horizontal = !compact, modifier = Modifier.fillMaxWidth(), actions = {
                state.actions.primary?.let { action ->
                    DetailTextButton(actionLabel(action, vm.playbackQueue()),
                        Modifier.testTag("series-primary").focusRequester(primaryFocus)) { playAction(action) }
                    if (action.kind == DetailActionKind.Continue) {
                        DetailTextButton(stringResource(com.dev.Tvivo.R.string.detail_start_over), Modifier.testTag("series-start-over")) {
                            vm.playbackQueue().firstOrNull { it.episodeId == action.episodeId }?.let { episode ->
                                scope.launch {
                                    vm.clearResumeNow(episode.episodeId)
                                    onPlayEpisode(episode, 0L, vm.playbackQueue())
                                }
                            }
                        }
                    }
                }
                state.actions.secondary?.let { action ->
                    DetailTextButton(actionLabel(action, vm.playbackQueue()),
                        Modifier.testTag("series-next")) { playAction(action) }
                }
                DetailTextButton(stringResource(if (state.isFavourite)
                    com.dev.Tvivo.R.string.series_remove_favourite else com.dev.Tvivo.R.string.series_add_favourite),
                    Modifier.testTag("series-favourite")) { vm.toggleFavourite(!state.isFavourite) }
            })
    }

    if (compact) {
        SeriesEpisodeList(state, vm::focusedEpisodeInSeason, vm::selectSeason,
            vm::rememberEpisodeFocus, vm::refresh,
            onPlay = { play(it, true) }, onMenu = {
                if (state.playback[it.episodeId]?.positionMs != null) menuEpisode = it
            },
            header = hero, modifier = Modifier.fillMaxSize().background(AppColors.Bg))
    } else {
        Row(Modifier.fillMaxSize().background(AppColors.Bg)
            .then(if (LocalIsTelevision.current || insideShell) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            .padding(32.dp)) {
            Column(Modifier.width(350.dp).verticalScroll(rememberScrollState())) { hero() }
            Spacer(Modifier.width(32.dp))
            SeriesEpisodeList(state, vm::focusedEpisodeInSeason, vm::selectSeason,
                vm::rememberEpisodeFocus, vm::refresh,
                onPlay = { play(it, true) }, onMenu = {
                    if (state.playback[it.episodeId]?.positionMs != null) menuEpisode = it
                },
                header = null, modifier = Modifier.weight(1f).fillMaxSize())
        }
    }

    resumePrompt?.let { (episode, position) ->
        ResumePrompt(episode.title, position,
            onResume = { resumePrompt = null; onPlayEpisode(episode, position, vm.playbackQueue()) },
            onStartOver = {
                resumePrompt = null
                scope.launch {
                    vm.clearResumeNow(episode.episodeId)
                    onPlayEpisode(episode, 0L, vm.playbackQueue())
                }
            }, onDismiss = { resumePrompt = null })
    }
    menuEpisode?.let { episode ->
        Dialog(onDismissRequest = { menuEpisode = null }) {
            Column(Modifier.fillMaxWidth().background(AppColors.Elevated).padding(20.dp)) {
                Text(episode.title, color = AppColors.Ink, style = TvType.title)
                if (state.playback[episode.episodeId]?.positionMs != null) {
                    DetailTextButton(stringResource(com.dev.Tvivo.R.string.episode_clear_resume)) {
                        vm.clearResume(episode.episodeId); menuEpisode = null
                    }
                }
            }
        }
    }
}

@Composable
private fun actionLabel(action: DetailAction, all: List<EpisodeEntity>): String {
    val number = all.firstOrNull { it.episodeId == action.episodeId }
        ?.let { "S${it.seasonNumber} · E${it.episodeNum}" }.orEmpty()
    val base = when (action.kind) {
        DetailActionKind.Continue -> stringResource(com.dev.Tvivo.R.string.episode_action_continue, number)
        DetailActionKind.Next -> stringResource(com.dev.Tvivo.R.string.episode_action_next, number)
        DetailActionKind.Play -> stringResource(com.dev.Tvivo.R.string.episode_action_play, number)
        DetailActionKind.WatchAgain -> stringResource(com.dev.Tvivo.R.string.episode_action_watch_again, number)
    }
    return action.remainingMs?.let { "$base · ${DetailPlaybackPolicy.remainingLabel(it)}" } ?: base
}

@Composable
internal fun SeriesEpisodeList(
    state: SeriesDetailUiState,
    focusedEpisodeInSeason: (Int) -> String?,
    onSelectSeason: (Int) -> Unit,
    onFocusedEpisode: (Int, String) -> Unit,
    onRetry: () -> Unit,
    onPlay: (EpisodeEntity) -> Unit,
    onMenu: (EpisodeEntity) -> Unit,
    header: (@Composable () -> Unit)?,
    modifier: Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val listFocus = remember { FocusRequester() }
    val targetId = state.selectedSeason?.let(focusedEpisodeInSeason)
        ?.takeIf { id -> state.episodes.any { it.episodeId == id } }
        ?: state.actions.primary?.episodeId?.takeIf { id -> state.episodes.any { it.episodeId == id } }
        ?: state.episodes.firstOrNull()?.episodeId
    val offset = (if (header == null) 1 else 2) +
        (if (state.isRefreshing) 1 else 0) + (if (state.error != null) 1 else 0)
    LaunchedEffect(state.selectedSeason) { listState.scrollToItem(0) }
    LazyColumn(state = listState, modifier = modifier.testTag("series-episode-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header != null) item("hero") { header() }
        item("seasons") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .testTag("series-seasons"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.seasons.forEach { season ->
                    DetailTextButton(season.label + if (state.selectedSeason == season.number) " ▾" else "",
                        Modifier.width(150.dp).testTag("season-${season.number}")
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown &&
                                    targetId != null) {
                                    scope.launch {
                                        val index = state.episodes.indexOfFirst { it.episodeId == targetId }
                                        listState.scrollToItem(index + offset)
                                        runCatching { listFocus.requestFocus() }
                                    }
                                    true
                                } else false
                            }) { onSelectSeason(season.number) }
                }
            }
        }
        if (state.isRefreshing) item("refreshing") {
            Text(stringResource(com.dev.Tvivo.R.string.episodes_updating), color = AppColors.Dim, style = TvType.label)
        }
        state.error?.let { error -> item("error") {
            Text(ErrorCopy.of(error).message, color = AppColors.Dim, style = TvType.label)
            DetailTextButton(stringResource(com.dev.Tvivo.R.string.common_retry)) { onRetry() }
        } }
        if (state.episodes.isEmpty()) item("status") {
            val message = when {
                state.isLoading -> context.getString(com.dev.Tvivo.R.string.episodes_loading)
                state.error != null -> context.getString(com.dev.Tvivo.R.string.episodes_unavailable)
                else -> context.getString(com.dev.Tvivo.R.string.episodes_none)
            }
            Text(message, color = AppColors.Dim, style = TvType.body)
        }
        items(state.episodes, key = { "episode:${it.episodeId}" }) { episode ->
            val progress = state.playback[episode.episodeId]
            EpisodeRow(episode, progress?.let(DetailPlaybackPolicy::rowState) ?: EpisodeRowState.Unstarted,
                progress?.positionMs, progress?.durationMs,
                highlighted = episode.episodeId == state.actions.primary?.episodeId,
                modifier = if (episode.episodeId == targetId) Modifier.focusRequester(listFocus) else Modifier,
                onFocus = { onFocusedEpisode(episode.seasonNumber, episode.episodeId) },
                onPlay = { onPlay(episode) }, onMenu = { onMenu(episode) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episode: EpisodeEntity,
    state: EpisodeRowState,
    positionMs: Long?,
    durationMs: Long?,
    highlighted: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onPlay: () -> Unit,
    onMenu: () -> Unit
) {
    val context = LocalContext.current
    val displayTitle = if (EpisodeTitlePolicy.isGenericTitle(episode.title))
        stringResource(com.dev.Tvivo.R.string.episode_generic_title, episode.episodeNum)
    else episode.title
    val stateLabel = stringResource(when (state) {
        EpisodeRowState.Unstarted -> com.dev.Tvivo.R.string.episode_state_unstarted
        EpisodeRowState.Partial -> com.dev.Tvivo.R.string.episode_state_partial
        EpisodeRowState.Watched -> com.dev.Tvivo.R.string.episode_watched
    })
    Column(modifier.fillMaxWidth().background(if (highlighted) AppColors.Accent.copy(alpha = 0.18f) else AppColors.Surface)
        .tvFocusFrame().testTag("episode-${episode.episodeId}")
        .onFocusChanged { if (it.isFocused) onFocus() }
        .onPreviewKeyEvent {
            if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU &&
                it.nativeKeyEvent.action == KeyEvent.ACTION_UP) { onMenu(); true } else false
        }
        .semantics { contentDescription = "$displayTitle, $stateLabel" }
        .combinedClickable(onClick = onPlay, onLongClick = onMenu)
        .padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (highlighted) Text("▶ ", color = AppColors.AccentText, style = TvType.label)
            if (EpisodeTitlePolicy.showOrdinal(episode.title)) {
                Text(episode.episodeNum.toString().padStart(2, '0'), color = AppColors.Dim,
                    style = TvType.label, modifier = Modifier.width(48.dp))
            }
            Text(displayTitle, color = AppColors.Ink, style = TvType.title,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            episode.durationSecs?.takeIf { it > 0 }?.let {
                Text(pluralStringResource(com.dev.Tvivo.R.plurals.duration_minutes, it / 60, it / 60),
                    color = AppColors.Dim, style = TvType.label)
            }
            if (state == EpisodeRowState.Watched) Text(" ✓", color = AppColors.Good, style = TvType.title)
        }
        episode.plot?.let { plot ->
            Text(plot, color = AppColors.Dim, style = TvType.label, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 48.dp, top = 4.dp))
        }
        if (state == EpisodeRowState.Partial && positionMs != null && durationMs != null && durationMs > 0) {
            Box(Modifier.fillMaxWidth().height(4.dp).padding(start = 48.dp).background(AppColors.Line)) {
                Box(Modifier.fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f))
                    .height(4.dp).background(AppColors.Accent))
            }
        }
    }
}
