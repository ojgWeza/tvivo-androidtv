package com.dev.Tvivo.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Text
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import com.dev.Tvivo.ui.browse.ResumePrompt
import com.dev.Tvivo.ui.common.ErrorCopy
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Season/episode picker for one show — the layer movies and live do not have.
 *
 * Deliberately the browse screen's layout rather than a new one: a rail of seasons on the
 * left, a list on the right, the same focus frame and the same LEFT/RIGHT contract. A
 * viewer arriving here from the Movies grid should not have to learn a second set of
 * navigation rules for the same D-pad.
 *
 * Back is handled by the caller, which returns to the shows grid.
 */
@Composable
fun SeriesDetailScreen(
    seriesId: Int,
    onPlayEpisode: (EpisodeEntity, Long) -> Unit
) {
    val application = LocalApplication()
    val viewModel: SeriesDetailViewModel = viewModel(
        key = "series:$seriesId",
        factory = viewModelFactory {
            initializer {
                SeriesDetailViewModel(application, createSavedStateHandle(), seriesId)
            }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var resumePrompt by remember { mutableStateOf<Pair<EpisodeEntity, Long>?>(null) }
    val episodeListFocus = remember { FocusRequester() }

    fun launch(episode: EpisodeEntity) {
        scope.launch {
            viewModel.pendingFocusEpisodeId = episode.episodeId
            val resumeMs = viewModel.resumePositionMs(episode.episodeId)
            if (resumeMs != null && resumeMs > 0) {
                resumePrompt = episode to resumeMs
            } else {
                onPlayEpisode(episode, 0L)
            }
        }
    }

    // Something must be focused on arrival. Without this the screen opens with focus
    // nowhere and the first D-pad press is spent finding it — the same defect class as
    // the login focus trap, and invisible in code review.
    var initialFocusDone by remember(seriesId) { mutableStateOf(false) }
    LaunchedEffect(state.episodes.isNotEmpty()) {
        if (!initialFocusDone && state.episodes.isNotEmpty()) {
            initialFocusDone = true
            // The episode list, not the season rail: playing an episode is why the user
            // opened the show, and the rail is one LEFT away.
            runCatching { episodeListFocus.requestFocus() }
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(Palette.Bg)) {
        SeasonRail(
            seasons = state.seasons,
            selectedSeason = state.selectedSeason,
            onSelect = { viewModel.selectSeason(it.number) },
            episodeListFocus = episodeListFocus
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                title = state.title,
                plot = state.plot,
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh
            )

            when {
                // Cached episodes always win over an error: a failed refresh must not take
                // a show the user could otherwise still play.
                state.episodes.isNotEmpty() -> EpisodeList(
                    episodes = state.episodes,
                    pendingFocusEpisodeId = viewModel.pendingFocusEpisodeId,
                    listFocusRequester = episodeListFocus,
                    onPlay = { launch(it) },
                    onFocused = { viewModel.pendingFocusEpisodeId = it.episodeId }
                )

                state.isLoading -> Message("Loading episodes…")

                state.error != null -> Message(ErrorCopy.of(state.error!!).message)

                // A show whose `get_series_info` parsed to nothing. Distinct from an
                // error, and the honest thing to say.
                else -> Message("No episodes listed for this show")
            }
        }
    }

    resumePrompt?.let { (episode, positionMs) ->
        ResumePrompt(
            title = episode.title,
            positionMs = positionMs,
            onResume = {
                resumePrompt = null
                onPlayEpisode(episode, positionMs)
            },
            onStartOver = {
                resumePrompt = null
                onPlayEpisode(episode, 0L)
            },
            onDismiss = { resumePrompt = null }
        )
    }
}

@Composable
private fun LocalApplication(): android.app.Application =
    androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application

@Composable
private fun SeasonRail(
    seasons: List<SeasonTab>,
    selectedSeason: Int?,
    onSelect: (SeasonTab) -> Unit,
    /** Where RIGHT goes, for the same reason [com.dev.Tvivo.ui.browse.CategoryRail] needs
     *  it: the 2D focus search otherwise picks the header's Refresh. */
    episodeListFocus: FocusRequester
) {
    LazyColumn(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .background(Palette.Surface)
            .focusGroup(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp)
    ) {
        items(seasons, key = { it.number }) { season ->
            var focused by remember { mutableStateOf(false) }
            val selected = season.number == selectedSeason
            val background = when {
                focused -> Palette.Accent
                selected -> Palette.Elevated
                else -> Palette.Surface
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { right = episodeListFocus }
                    .background(background)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { onSelect(season) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = season.label,
                    color = if (focused) Palette.OnAccent else Palette.Ink,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = season.episodeCount.toString(),
                    color = if (focused) Palette.OnAccent else Palette.Dim,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    plot: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 24.dp)) {
                Text(text = title, color = Palette.Ink, fontSize = 24.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                plot?.let {
                    Text(
                        text = it,
                        color = Palette.Dim,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            Text(
                text = "Refresh",
                color = Palette.AccentText,
                fontSize = 16.sp,
                modifier = Modifier
                    .tvFocusFrame()
                    .clickable { onRefresh() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

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
private fun EpisodeList(
    episodes: List<EpisodeEntity>,
    pendingFocusEpisodeId: String?,
    listFocusRequester: FocusRequester,
    onPlay: (EpisodeEntity) -> Unit,
    onFocused: (EpisodeEntity) -> Unit
) {
    val listState = rememberLazyListState()
    val restoreRequester = remember { FocusRequester() }
    var restored by remember(pendingFocusEpisodeId) { mutableStateOf(pendingFocusEpisodeId == null) }

    // Restoration is by episode id, not index: the season can have gained episodes while
    // a stream was playing, and the same rule as the grid applies.
    LaunchedEffect(pendingFocusEpisodeId, episodes) {
        if (restored || pendingFocusEpisodeId == null) return@LaunchedEffect
        val index = episodes.indexOfFirst { it.episodeId == pendingFocusEpisodeId }
        if (index >= 0) {
            listState.scrollToItem(index)
            runCatching { restoreRequester.requestFocus() }
        }
        restored = true
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .focusRequester(listFocusRequester),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 24.dp,
            vertical = 8.dp
        )
    ) {
        items(episodes, key = { it.episodeId }) { episode ->
            EpisodeRow(
                episode = episode,
                modifier = if (episode.episodeId == pendingFocusEpisodeId) {
                    Modifier.focusRequester(restoreRequester)
                } else {
                    Modifier
                },
                onPlay = { onPlay(episode) },
                onFocused = { onFocused(episode) }
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeEntity,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onFocused: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusFrame()
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clickable { onPlay() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = episode.episodeNum.toString().padStart(2, '0'),
            color = Palette.Dim,
            fontSize = 16.sp,
            modifier = Modifier.width(48.dp)
        )
        Text(
            text = episode.title,
            color = Palette.Ink,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        episode.durationSecs?.takeIf { it > 0 }?.let {
            Text(
                text = "${it / 60} min",
                color = Palette.Dim,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Palette.Ink, fontSize = 20.sp)
    }
}
