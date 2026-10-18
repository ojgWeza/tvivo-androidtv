package com.dev.Tvivo.playback

import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.repository.PlaybackStateRepository
import com.dev.Tvivo.diagnostics.NetworkEventKind
import com.dev.Tvivo.diagnostics.NetworkEventRecorder
import com.dev.Tvivo.diagnostics.NetworkEventSource
import com.dev.Tvivo.diagnostics.NetworkPlaybackState
import com.dev.Tvivo.diagnostics.DeepStreamCheckGate
import com.dev.Tvivo.playback.PlaybackEndPolicy
import com.dev.Tvivo.playback.PlaybackRetryPolicy
import com.dev.Tvivo.playback.PlaybackAttemptResult
import com.dev.Tvivo.playback.StallRecoveryPolicy
import com.dev.Tvivo.playback.StallDecision
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

/** The only ExoPlayer and stream owner. MediaSessionService supplies the media notification. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var playerReleased = false
    private lateinit var session: MediaSession
    private var sessionReleased = false
    private var stopRequested = false
    private var stopCommandQueued = false
    private var stopAck: SettableFuture<SessionResult>? = null
    private lateinit var handoff: PlaybackHandoff
    private var checkpointJob: Job? = null
    private var timerJob: Job? = null
    private var sleepTimer = SleepTimerPolicy()
    private var timerStopping = false
    private var timerNoticeReason = SleepTimerNoticeReason.None
    private var notificationWarningVisible = false
    private var timerForegroundStarted = false
    private var timerReceiverRegistered = false
    private lateinit var stopButton: CommandButton
    private lateinit var playPauseButton: CommandButton
    private var recoveryJob: Job? = null
    private var retryCount = 1
    private var playbackState = "idle"
    private var failureKind: PlaybackFailureKind? = null
    private var retryAtElapsedMs = -1L
    private var nextPromptVisible = false
    private var nextPromptCancelled = false
    private var nextPromptTitle: String? = null
    private val nextSelector = NextPlaybackSelector()
    private var savedTrackPreferences = TrackPreferences.AndroidDefaults
    private var manualAudioChoice: Int? = null
    private var manualSubtitleChoice: Int? = null
    private var savedPlaybackModes = PlaybackModePreferences.AndroidDefaults
    private var selectedNextForRequest: String? = null
    private var selectedNextId: String? = null
    private var recoveryOpen = false
    private var stallRecoveryOpen = false
    private var openTimeoutJob: Job? = null
    private var lastGoodPositionMs = 0L
    private var lastSamplePositionMs = 0L
    private var lastSampleAtMs = 0L
    private var hasPlaybackProgress = false
    private val stallPolicy = StallRecoveryPolicy(30_000L)

    override fun onCreate() {
        super.onCreate()
        if (SleepTimerEndedSnapshot.itemId != null) playbackState = "sleep_timer_ended"
        val preferences = PlayerPreferencesStore(this)
        scope.launch { preferences.tracks.collect { savedTrackPreferences = it } }
        scope.launch { preferences.modes.collect { savedPlaybackModes = it } }
        // Media-session notifications are permission-exempt; never gate playback on POST_NOTIFICATIONS.
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            )
            .setMp3ExtractorFlags(androidx.media3.extractor.mp3.Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
            .setMediaSourceFactory(DefaultMediaSourceFactory(
                SingleStreamDataSourceFactory(DefaultDataSource.Factory(this), accountPartition = { handoff.current?.accountId }), extractors))
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        PlaybackSessionPolicy.configureAudio(object : PlaybackSessionPolicy.AudioTarget {
            override fun handleAudioFocus(enabled: Boolean) {
                player.setAudioAttributes(audioAttributes, enabled)
            }
            override fun pauseWhenBecomingNoisy(enabled: Boolean) {
                player.setHandleAudioBecomingNoisy(enabled)
            }
        })
        applyTrackPreferences()
        handoff = PlaybackHandoff(object : PlaybackHandoff.Port {
            override suspend fun save(item: PlaybackHandoff.Item, ended: Boolean, allowCompletion: Boolean) {
                if (item.isLive || item.accountId == null || item.contentType == null || item.itemId == null) return
                val failed = playbackState == "retry" || playbackState == "reconnecting" || player.playerError != null
                val position = PlaybackSessionPolicy.positionToSave(
                    player.currentPosition, lastGoodPositionMs, failed && !ended)
                val duration = player.duration.takeIf { it > 0 } ?: 0L
                val canComplete = allowCompletion && !failed
                Log.i("PlaybackResume", "save begin type=${item.contentType} current=${player.currentPosition} " +
                    "sample=$lastGoodPositionMs chosen=$position duration=$duration ended=$ended " +
                    "completion=$canComplete timer=${sleepTimer.state}")
                try {
                    PlaybackStateRepository(AppDatabase.get(this@PlaybackService), item.accountId)
                        .savePosition(item.contentType, item.itemId, position, duration, ended, canComplete)
                    Log.i("PlaybackResume", "save complete type=${item.contentType} position=$position")
                } catch (failure: Exception) {
                    Log.w("PlaybackResume", "save failed type=${item.contentType} cause=${failure.javaClass.simpleName}")
                    throw failure
                }
            }

            override fun close() {
                if (NetworkPlaybackState.active) DeepStreamCheckGate.recordStreamStopped()
                NetworkPlaybackState.active = false
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
            }

            override fun open(item: PlaybackHandoff.Item, generation: Long) {
                Log.i("PlaybackResume", "open type=${item.contentType} resume=${item.resumeFromMs} " +
                    "live=${item.isLive} timer=${sleepTimer.state}")
                NetworkPlaybackState.active = true
                val sameItemRecovery = recoveryOpen
                if (!recoveryOpen) retryCount = 1
                playbackState = if (sameItemRecovery) "reconnecting" else "playing"
                retryAtElapsedMs = -1L
                if (!sameItemRecovery) failureKind = null
                lastGoodPositionMs = if (item.isLive) 0L else item.resumeFromMs.coerceAtLeast(0L)
                lastSamplePositionMs = lastGoodPositionMs
                lastSampleAtMs = android.os.SystemClock.elapsedRealtime()
                if (!recoveryOpen) hasPlaybackProgress = false
                stallPolicy.beginSession(generation, isRecovery = stallRecoveryOpen)
                recoveryOpen = false
                stallRecoveryOpen = false
                if (!sameItemRecovery) {
                    manualAudioChoice = null
                    manualSubtitleChoice = null
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                    applyTrackPreferences()
                }
                nextPromptVisible = false
                nextPromptCancelled = false
                selectedNextForRequest = null
                selectedNextId = null
                val media = MediaItem.Builder()
                    .setMediaId(generation.toString())
                    .setUri(item.url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(PlaybackSessionPolicy.displayTitle(item.title))
                            .setArtworkData(appArtwork(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            .build()
                    )
                    .build()
                player.setMediaItem(media, if (item.isLive) 0L else item.resumeFromMs.coerceAtLeast(0L))
                player.prepare()
                player.playWhenReady = true
                openTimeoutJob?.cancel()
                openTimeoutJob = scope.launch {
                    delay(PlaybackRetryPolicy.attemptTimeoutMs(item.isLive))
                    if (!handoff.isCurrent(generation) || !timerAllowsTransition()) return@launch
                    NetworkEventRecorder.occurrence(NetworkEventSource.PLAYBACK, item.accountId,
                        NetworkEventKind.TIMEOUT, "stream open")
                    val attempt = retryCount
                    failureKind = PlaybackFailurePolicy.classify(PlaybackAttemptResult.Timeout,
                        offline = isOffline())
                    if (PlaybackRetryPolicy.shouldRetry(PlaybackAttemptResult.Timeout, attempt,
                            PlaybackRetryPolicy.DEFAULT_ATTEMPTS, 0L)) {
                        NetworkEventRecorder.occurrence(NetworkEventSource.PLAYBACK, item.accountId,
                            NetworkEventKind.RETRY, "stream open timeout")
                        retryCount++
                        playbackState = "reconnecting"
                        val waitMs = PlaybackRetryPolicy.delayMs(attempt)
                        retryAtElapsedMs = SystemClock.elapsedRealtime() + waitMs
                        delay(waitMs)
                        if (handoff.isCurrent(generation) && timerAllowsTransition()) reopen(item, if (item.isLive) 0L else lastGoodPositionMs)
                    } else markFinalFailure()
                }
            }
        })
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                NetworkPlaybackState.selectedTrackBitrateMbps = tracks.groups.asSequence()
                    .flatMap { group -> (0 until group.length).asSequence().filter(group::isTrackSelected).map { group.getTrackFormat(it).bitrate } }
                    .filter { it > 0 }.maxOrNull()?.div(1_000_000.0)
                manualAudioChoice?.let { applyManualTrackChoice(C.TRACK_TYPE_AUDIO, it, tracks) }
                manualSubtitleChoice?.let { applyManualTrackChoice(C.TRACK_TYPE_TEXT, it, tracks) }
                updateTimerNotificationActions()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = updateTimerNotificationActions()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateTimerNotificationActions()
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && handoff.current?.isLive == true) openTimeoutJob?.cancel()
                if (state == Player.STATE_READY && playbackState == "reconnecting" && retryAtElapsedMs < 0) {
                    playbackState = "playing"
                    failureKind = null
                }
                if (state != Player.STATE_ENDED) return
                val generation = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                if (handoff.isCurrent(generation)) scope.launch {
                    yield()
                    if (!handoff.isCurrent(generation) || !timerAllowsTransition()) return@launch
                    if (PlaybackEndPolicy.isGenuineEnd(player.currentPosition, player.duration.takeIf { it > 0 }, true)) {
                        advanceAfterEnd()
                    } else {
                        failureKind = PlaybackFailurePolicy.classify(PlaybackAttemptResult.UnknownFailure, earlyEnd = true)
                        markFinalFailure()
                    }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                openTimeoutJob?.cancel()
                val generation = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                if (handoff.isCurrent(generation)) scope.launch {
                    yield()
                    if (!handoff.isCurrent(generation) || !timerAllowsTransition()) return@launch
                    val item = handoff.current ?: return@launch
                    val responseCode = generateSequence(error.cause) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                        .firstOrNull()?.responseCode
                    if (responseCode != null) NetworkEventRecorder.http(NetworkEventSource.PLAYBACK,
                        item.accountId, responseCode, "stream source")
                    else NetworkEventRecorder.failure(NetworkEventSource.PLAYBACK, item.accountId, error, "stream source")
                    val failure = when {
                        responseCode != null -> PlaybackRetryPolicy.httpSourceFailure(responseCode)
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                            PlaybackAttemptResult.UnsupportedMedia
                        error is ExoPlaybackException && error.type == ExoPlaybackException.TYPE_SOURCE ->
                            PlaybackAttemptResult.NetworkFailure
                        else -> PlaybackAttemptResult.DecodeFailure
                    }
                    failureKind = PlaybackFailurePolicy.classify(failure, responseCode, offline = isOffline())
                    if ((hasPlaybackProgress && !PlaybackRetryPolicy.mayReopenAfterProgress(failure)) ||
                        stallPolicy.recoveryAttempted) {
                        markFinalFailure()
                        return@launch
                    }
                    val attempt = retryCount
                    if (PlaybackRetryPolicy.shouldRetry(failure, attempt,
                            PlaybackRetryPolicy.DEFAULT_ATTEMPTS, 0L)) {
                        NetworkEventRecorder.occurrence(NetworkEventSource.PLAYBACK, item.accountId,
                            NetworkEventKind.RETRY, "source error")
                        retryCount++
                        playbackState = "reconnecting"
                        val waitMs = PlaybackRetryPolicy.delayMs(attempt)
                        retryAtElapsedMs = SystemClock.elapsedRealtime() + waitMs
                        delay(waitMs)
                        if (handoff.isCurrent(generation) && timerAllowsTransition()) reopen(item, if (item.isLive) 0L else lastGoodPositionMs)
                    } else markFinalFailure()
                }
            }
            override fun onRenderedFirstFrame() {
                openTimeoutJob?.cancel()
                if (playbackState == "reconnecting" && retryAtElapsedMs < 0) {
                    playbackState = "playing"
                    failureKind = null
                }
            }
        })
        stopButton = CommandButton.Builder().setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
            .setDisplayName(getString(com.dev.Tvivo.R.string.playback_stop))
            .setSessionCommand(PlaybackCommands.command(PlaybackCommands.STOP))
            .build()
        playPauseButton = CommandButton.Builder().setIconResId(android.R.drawable.ic_media_play)
            .setDisplayName("Play or pause").setPlayerCommand(Player.COMMAND_PLAY_PAUSE).build()
        session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent())
            .setCustomLayout(notificationLayout())
            .setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (!PlaybackSessionPolicy.mayControlSession(controller.uid, applicationInfo.uid, controller.isTrusted))
                    return MediaSession.ConnectionResult.reject()
                // Privileged commands (PLAY with a URL, tracks, timer) are for this app only;
                // trusted system controllers get the default commands plus STOP.
                val builder = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(PlaybackCommands.command(PlaybackCommands.STOP))
                    .add(PlaybackCommands.command(PlaybackCommands.NOTIFICATION_PREVIOUS))
                    .add(PlaybackCommands.command(PlaybackCommands.NOTIFICATION_NEXT))
                    .add(PlaybackCommands.command(PlaybackCommands.NOTIFICATION_REWIND))
                    .add(PlaybackCommands.command(PlaybackCommands.NOTIFICATION_FORWARD))
                if (PlaybackSessionPolicy.mayRunPrivilegedCommands(controller.uid, applicationInfo.uid)) {
                    listOf(
                        PlaybackCommands.PLAY, PlaybackCommands.STATUS, PlaybackCommands.SELECT_TRACK,
                        PlaybackCommands.RETRY, PlaybackCommands.CANCEL_NEXT,
                        PlaybackCommands.TIMER_START_PRESET, PlaybackCommands.TIMER_START_CUSTOM,
                        PlaybackCommands.TIMER_START_END_OF_ITEM, PlaybackCommands.TIMER_EXTEND_15,
                        PlaybackCommands.TIMER_CANCEL, PlaybackCommands.TIMER_STATUS,
                    ).forEach { builder.add(PlaybackCommands.command(it)) }
                }
                val commands: SessionCommands = builder.build()
                // These generic transport actions create a second Previous/Next row in
                // system media notifications alongside our queue-aware episode buttons.
                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .remove(Player.COMMAND_SEEK_TO_NEXT)
                    .build()
                return MediaSession.ConnectionResult.accept(commands, playerCommands)
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                if (PlaybackSessionPolicy.routeAction(
                        customCommand.customAction,
                        PlaybackSessionPolicy.mayControlSession(controller.uid, applicationInfo.uid, controller.isTrusted),
                ) == PlaybackSessionPolicy.Action.Stop
                ) {
                    stopAck?.let { return it }
                    if (stopRequested || stopCommandQueued)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_INFO_SKIPPED))
                    stopCommandQueued = true
                    val result = SettableFuture.create<SessionResult>().also { stopAck = it }
                    scope.launch {
                        try {
                            stopSession()
                        } catch (_: Exception) {
                            result.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                        }
                    }
                    return result
                }
                when (customCommand.customAction) {
                    PlaybackCommands.NOTIFICATION_PREVIOUS -> {
                        scope.launch {
                            if (timerAllowsTransition()) handoff.current?.let { current ->
                                queueItem(current, current.playlistIndex - 1)?.let { playQueueItem(current, it) }
                            }
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    PlaybackCommands.NOTIFICATION_NEXT -> {
                        scope.launch {
                            if (!timerAllowsTransition()) return@launch
                            val current = handoff.current
                            if (current != null) {
                                val mode = if (current.contentType == "series") savedPlaybackModes.episodeMode else savedPlaybackModes.movieMode
                                selectedNext(current, mode)?.let { playQueueItem(current, it) }
                            }
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    PlaybackCommands.NOTIFICATION_REWIND -> {
                        if (player.isCurrentMediaItemSeekable) player.seekBack()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    PlaybackCommands.NOTIFICATION_FORWARD -> {
                        if (player.isCurrentMediaItemSeekable) player.seekForward()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    PlaybackCommands.SELECT_TRACK -> {
                        val type = args.getInt(PlaybackCommands.TRACK_TYPE, -1)
                        val choice = args.getInt(PlaybackCommands.TRACK_CHOICE, -1)
                        if (stopRequested || type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT ||
                            choice < 0 || args.getString(PlaybackCommands.MEDIA_ID) != player.currentMediaItem?.mediaId ||
                            !applyManualTrackChoice(type, choice, player.currentTracks))
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                        if (type == C.TRACK_TYPE_AUDIO) manualAudioChoice = choice
                        else manualSubtitleChoice = choice
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    PlaybackCommands.PLAY -> {
                        if (DeepStreamCheckGate.isHeld)
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_INFO_SKIPPED))
                        if (stopRequested || stopCommandQueued)
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_INFO_SKIPPED))
                        val item = PlaybackCommands.item(args)
                            ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                        if (sleepTimer.state == SleepTimerState.Ended && handoff.current != null ||
                            sleepTimer.state != SleepTimerState.Ended && !timerAllowsTransition())
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_INFO_SKIPPED))
                        val result = SettableFuture.create<SessionResult>()
                        scope.launch {
                            try {
                                if (sleepTimer.state != SleepTimerState.Ended && !timerAllowsTransition()) {
                                    result.set(SessionResult(SessionResult.RESULT_INFO_SKIPPED))
                                    return@launch
                                }
                                val prefs = PlayerPreferencesStore(this@PlaybackService)
                                savedTrackPreferences = prefs.tracks.first()
                                savedPlaybackModes = prefs.modes.first()
                                if (sleepTimer.state != SleepTimerState.Ended && !timerAllowsTransition()) {
                                    result.set(SessionResult(SessionResult.RESULT_INFO_SKIPPED))
                                    return@launch
                                }
                                if (sleepTimer.state == SleepTimerState.Ended && handoff.current == null) {
                                    sleepTimer = SleepTimerPolicy()
                                    timerStopping = false
                                    timerNoticeReason = SleepTimerNoticeReason.None
                                }
                                if (handoff.current?.requestId != null && handoff.current?.requestId != item.requestId)
                                    timerNoticeReason = sleepTimer.manualItemChange().noticeReason
                                SleepTimerEndedSnapshot.clear()
                                handoff.play(item)
                                if (handoff.current != null) startCheckpointing()
                                val code = if (handoff.current?.requestId == item.requestId)
                                    SessionResult.RESULT_SUCCESS else SessionResult.RESULT_INFO_SKIPPED
                                result.set(SessionResult(code))
                            } catch (failure: Exception) {
                                result.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                            }
                        }
                        return result
                    }
                    PlaybackCommands.STATUS -> {
                        applyTimerDecision(sleepTimer.tick(SystemClock.elapsedRealtime()))
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, statusBundle()))
                    }
                    PlaybackCommands.TIMER_STATUS -> {
                        applyTimerDecision(sleepTimer.tick(SystemClock.elapsedRealtime()))
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, timerStatusBundle()))
                    }
                    PlaybackCommands.TIMER_START_PRESET, PlaybackCommands.TIMER_START_CUSTOM,
                    PlaybackCommands.TIMER_START_END_OF_ITEM -> {
                        if (handoff.current == null || !timerAllowsTransition())
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                        val now = SystemClock.elapsedRealtime()
                        val decision = when (customCommand.customAction) {
                            PlaybackCommands.TIMER_START_PRESET -> {
                                val minutes = args.getInt(PlaybackCommands.TIMER_MINUTES, -1)
                                if (minutes !in SleepTimerPolicy.presetMinutes)
                                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                                sleepTimer.startPreset(now, minutes)
                            }
                            PlaybackCommands.TIMER_START_CUSTOM -> sleepTimer.startCustom(
                                now, args.getInt(PlaybackCommands.TIMER_MINUTES, SleepTimerPolicy.defaultDurationMinutes))
                            else -> {
                                val item = handoff.current!!
                                val identity = item.itemId ?: item.requestId
                                if (!sleepTimer.tryStartEndOfCurrentItem(identity, item.isLive))
                                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                                SleepTimerDecision(sleepTimer.state)
                            }
                        }
                        timerNoticeReason = decision.noticeReason
                        updateTimerNotificationActions()
                        startTimerTicker()
                        showTimerForeground()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, timerStatusBundle()))
                    }
                    PlaybackCommands.TIMER_EXTEND_15 -> {
                        applyTimerDecision(sleepTimer.extend(SystemClock.elapsedRealtime()))
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, timerStatusBundle()))
                    }
                    PlaybackCommands.TIMER_CANCEL -> {
                        if (timerAllowsTransition()) applyTimerDecision(sleepTimer.cancel())
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, timerStatusBundle()))
                    }
                    PlaybackCommands.RETRY -> {
                        if (!timerAllowsTransition())
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_INFO_SKIPPED))
                        val result = SettableFuture.create<SessionResult>()
                        scope.launch {
                            try {
                                if (!timerAllowsTransition()) {
                                    result.set(SessionResult(SessionResult.RESULT_INFO_SKIPPED))
                                    return@launch
                                }
                                retryCount = 1
                                playbackState = "reconnecting"
                                retryAtElapsedMs = -1L
                                recoveryOpen = true
                                handoff.reopenCurrent(if (handoff.current?.isLive == true) 0L else lastGoodPositionMs)
                                result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                            } catch (_: Exception) {
                                markFinalFailure()
                                result.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                            }
                        }
                        return result
                    }
                    PlaybackCommands.CANCEL_NEXT -> {
                        nextPromptCancelled = true
                        nextPromptVisible = false
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).build()
        ContextCompat.registerReceiver(this, timerActionReceiver, IntentFilter().apply {
            addAction(PlaybackCommands.TIMER_EXTEND_15)
            addAction(PlaybackCommands.TIMER_CANCEL)
            addAction(PlaybackCommands.STOP)
            addAction(PlaybackCommands.TIMER_PLAY_PAUSE_NOTIFICATION)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        timerReceiverRegistered = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session.takeIf {
            PlaybackSessionPolicy.mayControlSession(controllerInfo.uid, applicationInfo.uid, controllerInfo.isTrusted)
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopRequested || intent == null && handoff.current == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private val timerActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (PlaybackSleepTimerGate.notificationAction(intent.action)) {
                PlaybackSleepTimerGate.NotificationAction.Extend ->
                    applyTimerDecision(sleepTimer.extend(SystemClock.elapsedRealtime()))
                PlaybackSleepTimerGate.NotificationAction.Cancel ->
                    if (timerAllowsTransition()) applyTimerDecision(sleepTimer.cancel())
                PlaybackSleepTimerGate.NotificationAction.Stop -> scope.launch { stopSession() }
                PlaybackSleepTimerGate.NotificationAction.PlayPause ->
                    if (player.isPlaying) player.pause() else player.play()
                PlaybackSleepTimerGate.NotificationAction.None -> Unit
            }
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (stopRequested || sessionReleased) return
        if (sleepTimer.state == SleepTimerState.Armed || sleepTimer.state == SleepTimerState.Warning) {
            showTimerForeground()
        } else {
            if (timerForegroundStarted) {
                timerForegroundStarted = false
                stopForeground(true)
            }
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    private fun sessionActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, com.dev.Tvivo.ui.player.PlayerActivity::class.java)
            .setAction(ACTION_REOPEN_SESSION)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(com.dev.Tvivo.ui.player.PlayerActivity.EXTRA_REOPEN_SESSION, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun appArtwork(): ByteArray {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val drawable = requireNotNull(ContextCompat.getDrawable(this, com.dev.Tvivo.R.drawable.ic_mark))
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun startCheckpointing() {
        if (checkpointJob?.isActive == true) return
        checkpointJob = scope.launch {
            recoveryJob?.cancel()
            recoveryJob = launch {
                while (isActive) {
                    delay(1_000L)
                    if (!timerAllowsTransition()) continue
                    val item = handoff.current ?: continue
                    updateNextPrompt(item)
                    val generation = handoff.generation
                    val now = android.os.SystemClock.elapsedRealtime()
                    val position = player.currentPosition.coerceAtLeast(0L)
                    val delta = position - lastSamplePositionMs
                    val elapsed = (now - lastSampleAtMs).coerceAtLeast(0L)
                    if (delta > 0L && delta <= elapsed * 2L + 1_000L) {
                        lastGoodPositionMs = position
                        hasPlaybackProgress = true
                    }
                    lastSamplePositionMs = position
                    lastSampleAtMs = now
                    val eligible = item.isLive || player.isCurrentMediaItemSeekable
                    when (stallPolicy.observe(generation, player.playbackState == Player.STATE_READY,
                        eligible && player.playWhenReady, position, now)) {
                        StallDecision.Recover -> {
                            NetworkEventRecorder.occurrence(NetworkEventSource.PLAYBACK, item.accountId,
                                NetworkEventKind.STALL, "playback position stopped advancing")
                            NetworkEventRecorder.occurrence(NetworkEventSource.PLAYBACK, item.accountId,
                                NetworkEventKind.RETRY, "stall recovery")
                            playbackState = "reconnecting"
                            failureKind = PlaybackFailurePolicy.classify(PlaybackAttemptResult.Timeout, stalled = true)
                            retryAtElapsedMs = -1L
                            reopen(item, if (item.isLive) 0L else lastGoodPositionMs, isStallRecovery = true)
                        }
                        StallDecision.GiveUp -> {
                            failureKind = PlaybackFailurePolicy.classify(PlaybackAttemptResult.Timeout, stalled = true)
                            markFinalFailure()
                        }
                        StallDecision.None -> Unit
                    }
                }
            }
            while (isActive) {
                delay(10_000L)
                handoff.checkpoint(allowCompletion = sleepTimer.state == SleepTimerState.Off)
            }
        }
    }

    private suspend fun reopen(item: PlaybackHandoff.Item, positionMs: Long, isStallRecovery: Boolean = false) {
        if (!timerAllowsTransition()) return
        recoveryOpen = true
        stallRecoveryOpen = isStallRecovery
        try {
            handoff.reopenCurrent(positionMs)
        } catch (_: Exception) {
            recoveryOpen = false
            stallRecoveryOpen = false
            markFinalFailure()
        }
    }

    private suspend fun stopSession(ended: Boolean = false, timed: Boolean = false) {
        if (stopRequested) return
        val acknowledgement = stopAck ?: SettableFuture.create<SessionResult>().also { stopAck = it }
        stopRequested = true
        val timerArmed = sleepTimer.state != SleepTimerState.Off
        Log.i("PlaybackResume", "stop begin ended=$ended timed=$timed timer=${sleepTimer.state} " +
            "active=${handoff.current != null}")
        openTimeoutJob?.cancel()
        openTimeoutJob = null
        timerJob?.cancel()
        timerJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        checkpointJob?.cancel()
        checkpointJob = null
        if (!timed) {
            sleepTimer.cancel()
            SleepTimerEndedSnapshot.clear()
            updateTimerNotificationActions()
        }
        var saved = false
        try {
            handoff.stop(ended, allowCompletion = PlaybackSessionPolicy.allowCompletionOnStop(
                ended, timed, timerArmed, playbackState == "retry"))
            saved = true
            Log.i("PlaybackResume", "stop complete saved=true")
        } finally {
            if (!saved) Log.w("PlaybackResume", "stop complete saved=false")
            acknowledgement.set(SessionResult(if (saved) SessionResult.RESULT_SUCCESS
                else SessionResult.RESULT_ERROR_UNKNOWN))
            try {
                if (!sessionReleased) {
                    sessionReleased = true
                    session.release()
                }
            } finally {
                timerForegroundStarted = false
                try {
                    if (!playerReleased) {
                        playerReleased = true
                        player.release()
                    }
                } finally {
                    try {
                        stopForeground(true)
                        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .cancel(TIMER_NOTIFICATION_ID)
                    } finally {
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun applyTrackPreferences() {
        player.trackSelectionParameters = player.trackSelectionParameters.withPreferences(savedTrackPreferences)
    }

    private fun applyManualTrackChoice(type: Int, choice: Int, tracks: Tracks): Boolean {
        val updated = player.trackSelectionParameters.withManualTrackChoice(type, choice, tracks)
            ?: return false
        if (updated != player.trackSelectionParameters) player.trackSelectionParameters = updated
        return true
    }

    private suspend fun updateNextPrompt(item: PlaybackHandoff.Item) {
        if (!timerAllowsTransition()) return
        val kind = when {
            item.isLive -> return
            item.contentType == com.dev.Tvivo.data.local.entities.TYPE_VOD -> StreamKind.Movie
            item.contentType == "series" -> StreamKind.Episode
            else -> return
        }
        val preferences = savedPlaybackModes
        val mode = if (kind == StreamKind.Movie) preferences.movieMode else preferences.episodeMode
        if (!PlaybackModeLogic.shouldShowNextPrompt(mode)) { nextPromptVisible = false; return }
        val next = selectedNext(item, mode)
        val duration = player.duration.takeIf { it > 0 } ?: return
        val lead = NextPrompt.leadMilliseconds(kind, duration)
        val show = next != null && lead > 0 && player.currentPosition >= duration - lead
        if (!show) nextPromptCancelled = false // seek-out/re-entry re-arms prompt
        nextPromptVisible = show && !nextPromptCancelled
        nextPromptTitle = next?.title
    }

    private suspend fun advanceAfterEnd() {
        if (!timerAllowsTransition()) return
        val item = handoff.current ?: return
        if (!PlaybackEndPolicy.isGenuineEnd(player.currentPosition, player.duration.takeIf { it > 0 }, true)) {
            failureKind = PlaybackFailurePolicy.classify(PlaybackAttemptResult.UnknownFailure, earlyEnd = true)
            markFinalFailure()
            return
        }
        val timerDecision = sleepTimer.genuineNaturalEnd(
            item.itemId ?: item.requestId, true, handoff.generation, handoff.generation)
        applyTimerDecision(timerDecision, naturalEnd = true)
        if (timerDecision.stopPlayback || !timerAllowsTransition()) return
        val kind = if (item.contentType == com.dev.Tvivo.data.local.entities.TYPE_VOD) StreamKind.Movie else StreamKind.Episode
        val prefs = savedPlaybackModes
        val mode = if (kind == StreamKind.Movie) prefs.movieMode else prefs.episodeMode
        val next = selectedNext(item, mode)
        if (mode == PlaybackMode.Off || next == null) { stopSession(ended = true); return }
        handoff.play(item.copy(requestId = java.util.UUID.randomUUID().toString(), url = next.url,
            title = next.title, itemId = next.id, resumeFromMs = 0L,
            playlistIndex = item.playlist.indexOf(next)), previousEnded = true)
    }

    private fun selectedNext(item: PlaybackHandoff.Item, mode: PlaybackMode): PlaybackHandoff.QueueItem? {
        if (selectedNextForRequest != item.requestId) {
            selectedNextForRequest = item.requestId
            selectedNextId = if (mode == PlaybackMode.Off) null else nextSelector.selectNext(
                item.playlist.map { it.id }, item.itemId.orEmpty(), shuffle = mode == PlaybackMode.Shuffle
            )
        }
        return item.playlist.firstOrNull { it.id == selectedNextId }
    }

    private fun queueItem(item: PlaybackHandoff.Item, index: Int): PlaybackHandoff.QueueItem? =
        item.playlist.getOrNull(index)

    private suspend fun playQueueItem(current: PlaybackHandoff.Item, target: PlaybackHandoff.QueueItem) {
        if (!timerAllowsTransition() || stopRequested || stopCommandQueued) return
        handoff.play(current.copy(requestId = java.util.UUID.randomUUID().toString(), url = target.url,
            title = target.title, itemId = target.id, resumeFromMs = 0L,
            playlistIndex = current.playlist.indexOf(target)))
    }

    private fun statusBundle() = timerStatusBundle().apply {
        val item = handoff.current
        putString(PlaybackCommands.REQUEST_ID, item?.requestId ?: SleepTimerEndedSnapshot.requestId)
        putString(PlaybackCommands.TITLE, item?.title ?: SleepTimerEndedSnapshot.title)
        putString(PlaybackCommands.ITEM_ID, item?.itemId ?: SleepTimerEndedSnapshot.itemId)
        putInt("queue_index", item?.playlistIndex ?: -1)
        putLong(PlaybackCommands.GENERATION, handoff.generation)
        putString(PlaybackCommands.STATE, playbackState)
        putString(PlaybackCommands.FAILURE_KIND, failureKind?.name)
        if (playbackState == "reconnecting") {
            val remainingMs = if (retryAtElapsedMs < 0) -1L
                else (retryAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val progress = PlaybackFailurePolicy.retryProgress(retryCount,
                PlaybackRetryPolicy.DEFAULT_ATTEMPTS, remainingMs)
            putInt(PlaybackCommands.RETRY_ATTEMPT, progress.attempt)
            putInt(PlaybackCommands.RETRY_MAX, progress.maxAttempts)
            putInt(PlaybackCommands.RETRY_NEXT_SECONDS, progress.nextInSeconds ?: -1)
        }
        putBoolean(PlaybackCommands.NEXT_PROMPT, nextPromptVisible)
        putString(PlaybackCommands.NEXT_TITLE, nextPromptTitle)
    }

    private fun timerStatusBundle() = Bundle().apply {
        val now = SystemClock.elapsedRealtime()
        putString(PlaybackCommands.TIMER_STATE,
            if (SleepTimerEndedSnapshot.itemId != null && handoff.current == null)
                SleepTimerState.Ended.name else sleepTimer.state.name)
        putString(PlaybackCommands.TIMER_MODE, sleepTimer.mode?.name)
        putLong(PlaybackCommands.TIMER_DEADLINE_ELAPSED_MS, sleepTimer.deadlineMilliseconds ?: -1L)
        putLong(PlaybackCommands.TIMER_REMAINING_MS,
            PlaybackSleepTimerGate.remainingMilliseconds(sleepTimer, now))
        putString(PlaybackCommands.TIMER_TARGET_ITEM_ID, sleepTimer.targetItemIdentity)
        putString(PlaybackCommands.TIMER_NOTICE_REASON, timerNoticeReason.name)
        putString(PlaybackCommands.TIMER_ENDED_ITEM_ID, SleepTimerEndedSnapshot.itemId)
    }

    private fun startTimerTicker() {
        if (timerJob?.isActive == true) return
        timerJob = scope.launch {
            while (isActive && sleepTimer.state != SleepTimerState.Off &&
                sleepTimer.state != SleepTimerState.Ended) {
                delay(1_000L)
                applyTimerDecision(sleepTimer.tick(SystemClock.elapsedRealtime()))
            }
        }
    }

    private fun timerAllowsTransition(): Boolean {
        val decision = sleepTimer.tick(SystemClock.elapsedRealtime())
        applyTimerDecision(decision)
        return PlaybackSleepTimerGate.permitsTransition(decision, timerStopping)
    }

    private fun applyTimerDecision(decision: SleepTimerDecision, naturalEnd: Boolean = false) {
        val wasWarning = notificationWarningVisible
        if (decision.noticeReason != SleepTimerNoticeReason.None)
            timerNoticeReason = decision.noticeReason
        if (decision.state == SleepTimerState.Off) {
            timerJob?.cancel()
            timerJob = null
        }
        if (decision.showWarning || decision.state == SleepTimerState.Armed ||
            decision.state == SleepTimerState.Off) updateTimerNotificationActions()
        if (decision.showWarning || decision.state == SleepTimerState.Armed && wasWarning)
            showTimerForeground()
        if (decision.state == SleepTimerState.Off) returnToMediaNotification()
        if (decision.stopPlayback) triggerTimedStop(ended = naturalEnd)
    }

    private fun triggerTimedStop(ended: Boolean = false) {
        if (timerStopping) return
        timerStopping = true
        val item = handoff.current
        SleepTimerEndedSnapshot.record(item)
        playbackState = "sleep_timer_ended"
        nextPromptVisible = false
        // Let a visible controller render the ended view before release. Disconnection also
        // reads the process-local snapshot, because this notification is best effort.
        if (::session.isInitialized && !sessionReleased)
            session.broadcastCustomCommand(PlaybackCommands.command(PlaybackCommands.TIMER_ENDED), statusBundle())
        openTimeoutJob?.cancel()
        recoveryJob?.cancel()
        checkpointJob?.cancel()
        scope.launch { stopSession(ended = ended, timed = true) }
    }

    private fun markFinalFailure() {
        if (!timerAllowsTransition()) return
        if (playbackState == "retry") return
        if (failureKind == null) failureKind = PlaybackFailureKind.Unknown
        playbackState = "retry"
        retryAtElapsedMs = -1L
        openTimeoutJob?.cancel()
        recoveryJob?.cancel()
        scope.launch { handoff.checkpoint(allowCompletion = false) }
        applyTimerDecision(sleepTimer.finalFailure())
    }

    private fun updateTimerNotificationActions() {
        if (!::session.isInitialized) return
        val warning = PlaybackCommands.TIMER_EXTEND_15 in
            PlaybackSleepTimerGate.notificationActions(sleepTimer.state)
        notificationWarningVisible = warning
        session.setCustomLayout(notificationLayout())
    }

    private fun isOffline(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return true
        return manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true
    }

    private fun notificationLayout(): List<CommandButton> {
        if (!::stopButton.isInitialized || !::playPauseButton.isInitialized) return emptyList()
        val current = handoff.current
        val buttons = mutableListOf<CommandButton>()
        if (current != null && current.playlistIndex > 0)
            buttons += notificationButton(android.R.drawable.ic_media_previous, "Previous episode", PlaybackCommands.NOTIFICATION_PREVIOUS)
        buttons += CommandButton.Builder()
            .setIconResId(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            .setDisplayName(if (player.isPlaying) "Pause" else "Play")
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE).build()
        val mode = current?.let { if (it.contentType == "series") savedPlaybackModes.episodeMode else savedPlaybackModes.movieMode }
            ?: PlaybackMode.Off
        if (current != null && selectedNext(current, mode) != null)
            buttons += notificationButton(android.R.drawable.ic_media_next, "Next episode", PlaybackCommands.NOTIFICATION_NEXT)
        if (current?.isLive != true && player.isCurrentMediaItemSeekable) {
            buttons += notificationButton(android.R.drawable.ic_media_rew, "Rewind 10 seconds", PlaybackCommands.NOTIFICATION_REWIND)
            buttons += notificationButton(android.R.drawable.ic_media_ff, "Forward 10 seconds", PlaybackCommands.NOTIFICATION_FORWARD)
        }
        buttons += stopButton
        if (notificationWarningVisible) {
            buttons += notificationButton(android.R.drawable.ic_menu_add, "Add 15 minutes", PlaybackCommands.TIMER_EXTEND_15)
            buttons += notificationButton(android.R.drawable.ic_menu_close_clear_cancel, "Turn off timer", PlaybackCommands.TIMER_CANCEL)
        }
        return buttons
    }

    private fun notificationButton(icon: Int, label: String, command: String) =
        CommandButton.Builder().setIconResId(icon).setDisplayName(label)
            .setSessionCommand(PlaybackCommands.command(command)).build()

    private fun timerAction(action: String, requestCode: Int) = PendingIntent.getBroadcast(
        this, requestCode, Intent(action).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun showTimerForeground() {
        if (!::session.isInitialized ||
            sleepTimer.state != SleepTimerState.Armed && sleepTimer.state != SleepTimerState.Warning) return
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(TIMER_CHANNEL_ID) == null)
                manager.createNotificationChannel(NotificationChannel(
                    TIMER_CHANNEL_ID, "Playback timer", NotificationManager.IMPORTANCE_LOW))
        }
        val warning = sleepTimer.state == SleepTimerState.Warning
        val style = MediaStyleNotificationHelper.MediaStyle(session)
            .setShowActionsInCompactView(*(if (warning) intArrayOf(0, 1, 2) else intArrayOf(0, 1)))
        val notification = NotificationCompat.Builder(this, TIMER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(handoff.current?.title ?: "Now playing")
            .setContentText(if (warning) "Sleep timer: 2 minutes left" else "Sleep timer on")
            .setContentIntent(sessionActivityPendingIntent())
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .addAction(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (player.isPlaying) "Pause" else "Play", timerAction(PlaybackCommands.TIMER_PLAY_PAUSE_NOTIFICATION, 11))
            .apply {
                if (warning) {
                    addAction(android.R.drawable.ic_menu_add, "Add 15 minutes",
                        timerAction(PlaybackCommands.TIMER_EXTEND_15, 12))
                    addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off timer",
                        timerAction(PlaybackCommands.TIMER_CANCEL, 13))
                }
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                timerAction(PlaybackCommands.STOP, 14))
            .build()
        if (!timerForegroundStarted) {
            ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
            timerForegroundStarted = true
        }
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(TIMER_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(TIMER_NOTIFICATION_ID, notification)
    }

    private fun returnToMediaNotification() {
        if (!timerForegroundStarted || !::session.isInitialized) return
        timerForegroundStarted = false
        super.onUpdateNotification(session,
            player.playWhenReady && (player.playbackState == Player.STATE_READY ||
                player.playbackState == Player.STATE_BUFFERING))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scope.launch { stopSession() }
    }

    override fun onDestroy() {
        NetworkPlaybackState.active = false
        if (timerReceiverRegistered) unregisterReceiver(timerActionReceiver)
        timerJob?.cancel()
        recoveryJob?.cancel()
        checkpointJob?.cancel()
        if (!playerReleased) {
            player.stop()
            player.clearMediaItems()
        }
        if (!sessionReleased) session.release()
        if (!playerReleased) player.release()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val ACTION_REOPEN_SESSION = "com.dev.Tvivo.playback.REOPEN_SESSION"
        const val TIMER_CHANNEL_ID = "playback_timer"
        const val TIMER_NOTIFICATION_ID = 1001
    }
}

internal fun TrackSelectionParameters.withPreferences(tracks: TrackPreferences): TrackSelectionParameters {
    return buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, tracks.subtitles == PreferredSubtitles.Off)
        .setPreferredAudioLanguage(TrackSelectionPolicy.normalizeLanguageCode(tracks.audio.languageCode))
        .setPreferredTextLanguage(TrackSelectionPolicy.normalizeLanguageCode(tracks.subtitles.languageCode))
        .build()
}

/** Resolve the menu ordinal against the service's own track groups, not controller copies. */
internal fun TrackSelectionParameters.withManualTrackChoice(
    type: Int, choice: Int, tracks: Tracks,
): TrackSelectionParameters? {
    if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT || choice < 0) return null
    val refs = tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
    }
    val selected = if (type == C.TRACK_TYPE_TEXT) choice - 1 else choice
    if (selected >= refs.size) return null
    val builder = buildUpon().clearOverridesOfType(type)
    if (type == C.TRACK_TYPE_TEXT) builder.setTrackTypeDisabled(type, selected < 0)
    if (selected >= 0) {
        val (group, index) = refs[selected]
        builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
    }
    return builder.build()
}

/** Kept only for this process; a fresh process cannot resurrect an expired timer. */
internal object SleepTimerEndedSnapshot {
    var requestId: String? = null
        private set
    var title: String? = null
        private set
    var itemId: String? = null
        private set

    fun record(item: PlaybackHandoff.Item?) {
        requestId = item?.requestId
        title = item?.title
        itemId = item?.itemId ?: item?.requestId
    }

    fun clear() {
        requestId = null
        title = null
        itemId = null
    }
}
