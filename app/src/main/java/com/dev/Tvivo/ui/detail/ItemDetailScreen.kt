package com.dev.Tvivo.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Text
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.playback.DetailPlaybackPolicy
import com.dev.Tvivo.playback.MovieActionKind
import com.dev.Tvivo.ui.adaptive.LocalWindowSizeClass
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.WindowSizeClass
import com.dev.Tvivo.ui.common.ErrorCopy
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.launch

/** Movie/live pre-run page. Series cards route straight to SeriesDetailScreen. */
@Composable
fun ItemDetailScreen(
    contentType: ContentType,
    itemId: Int,
    credentials: Credentials? = null,
    onPlay: (resumeFromMs: Long, extension: String?, title: String, knownDurationMs: Long) -> Unit,
    onOpenEpisodes: () -> Unit,
    insideShell: Boolean = false,
    stateOverride: ItemDetailUiState? = null,
    onToggleFavouriteOverride: () -> Unit = {},
    onClearResumeOverride: () -> Unit = {}
) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val context = LocalContext.current
    val vm: ItemDetailViewModel? = if (stateOverride == null) viewModel(
        key = "detail:${credentials?.let(AccountIdentity::of)}:${contentType.name}:$itemId",
        factory = viewModelFactory { initializer { ItemDetailViewModel(application, contentType, itemId, requireNotNull(credentials)) } }
    ) else null
    val state = stateOverride ?: requireNotNull(vm).state.collectAsStateWithLifecycle().value
    val primaryFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val isSeries = contentType == ContentType.SERIES
    val isLive = contentType == ContentType.LIVE
    var pendingPlay by remember(itemId) { mutableStateOf(false) }
    LaunchedEffect(state.isLoading, state.notFound, pendingPlay) {
        if (pendingPlay && !state.isLoading) {
            pendingPlay = false
            if (!state.notFound) {
                if (isSeries) onOpenEpisodes() else onPlay(
                    if (DetailPlaybackPolicy.movieActions(state.resumeFromMs,
                            state.durationMs ?: state.durationMinutes?.times(60_000L)).primary == MovieActionKind.Resume)
                        state.resumeFromMs ?: 0L else 0L,
                    state.extension, state.title,
                    state.durationMs ?: state.durationMinutes?.toLong()?.times(60_000L) ?: 0L)
            }
        }
    }
    val compact = LocalWindowSizeClass.current == WindowSizeClass.Compact
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm?.refreshPlayback()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && !state.notFound) repeat(3) {
            withFrameNanos { }
            if (runCatching { primaryFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    if (state.notFound) {
        Box(Modifier.fillMaxSize().background(AppColors.Bg), contentAlignment = Alignment.Center) {
            Text(stringResource(com.dev.Tvivo.R.string.detail_unavailable), color = AppColors.Dim, style = TvType.body)
        }
        return
    }

    Column(Modifier.fillMaxSize().background(AppColors.Bg)
        .then(if (LocalIsTelevision.current || insideShell) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
        .verticalScroll(rememberScrollState())
        .padding(if (compact) 16.dp else 48.dp)) {
        if (state.isLoading) Text(stringResource(com.dev.Tvivo.R.string.detail_loading), color = AppColors.Dim, style = TvType.label)
        DetailHero(
            title = state.title,
            imageUrl = state.imageUrl,
            plot = state.plot,
            quality = state.quality,
            year = state.year,
            rating = state.rating,
            durationMinutes = state.durationMinutes,
            genre = state.genre,
            cast = state.cast,
            isLive = isLive,
            horizontal = !compact,
            modifier = Modifier.fillMaxWidth(),
            actions = {
                val resume = state.resumeFromMs
                val movie = DetailPlaybackPolicy.movieActions(resume,
                    state.durationMs ?: state.durationMinutes?.times(60_000L))
                val primaryLabel = when {
                    isSeries -> stringResource(com.dev.Tvivo.R.string.detail_view_episodes)
                    movie.primary == MovieActionKind.Resume -> {
                        val remaining = movie.remainingMs
                        if (remaining != null) {
                            val minutes = (remaining.coerceAtLeast(0L) / 60_000L).coerceAtLeast(1L)
                            if (minutes >= 60) stringResource(com.dev.Tvivo.R.string.detail_resume_hours,
                                minutes / 60, minutes % 60)
                            else stringResource(com.dev.Tvivo.R.string.detail_resume_minutes, minutes)
                        } else stringResource(com.dev.Tvivo.R.string.detail_resume)
                    }
                    else -> stringResource(com.dev.Tvivo.R.string.player_play)
                }
                DetailTextButton(if (pendingPlay) stringResource(com.dev.Tvivo.R.string.detail_loading) else primaryLabel,
                    Modifier.testTag("detail-play").focusRequester(primaryFocus)) {
                    if (state.isLoading) pendingPlay = true
                    else if (isSeries) onOpenEpisodes() else onPlay(
                        if (movie.primary == MovieActionKind.Resume) resume ?: 0L else 0L,
                        state.extension, state.title,
                        state.durationMs ?: state.durationMinutes?.toLong()?.times(60_000L) ?: 0L)
                }
                if (!state.isLoading && movie.showStartOver) DetailTextButton(stringResource(com.dev.Tvivo.R.string.detail_start_over), Modifier.testTag("detail-start-over")) {
                    scope.launch {
                        if (vm != null) vm.clearResumeNow() else onClearResumeOverride()
                        onPlay(0L, state.extension, state.title,
                            state.durationMs ?: state.durationMinutes?.toLong()?.times(60_000L) ?: 0L)
                    }
                }
                DetailTextButton(stringResource(if (state.isFavourite)
                    com.dev.Tvivo.R.string.menu_remove_favourite else com.dev.Tvivo.R.string.menu_add_favourite),
                    Modifier.testTag("detail-favourite")) {
                    if (vm != null) vm.toggleFavourite() else onToggleFavouriteOverride()
                }
                state.trailer?.let { trailer ->
                    DetailTextButton(stringResource(com.dev.Tvivo.R.string.detail_watch_trailer), Modifier.testTag("detail-trailer")) {
                        val url = if (trailer.startsWith("https://")) trailer
                            else "https://www.youtube.com/watch?v=${Uri.encode(trailer)}"
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }
                }
            }
        )
        state.plotError?.let { error ->
            Text(ErrorCopy.of(error).message, color = AppColors.Dim, style = TvType.label)
            DetailTextButton(stringResource(com.dev.Tvivo.R.string.detail_retry)) { vm?.retryPlot() }
        }
    }
}
