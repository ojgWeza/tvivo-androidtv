package com.dev.Tvivo.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.core.content.ContextCompat
import com.dev.Tvivo.playback.HeldSeekAccelerator
import com.dev.Tvivo.playback.PlaybackCommands
import com.dev.Tvivo.playback.PlaybackHandoff
import com.dev.Tvivo.playback.PlaybackService
import com.dev.Tvivo.playback.SleepTimerEndedSnapshot
import com.dev.Tvivo.playback.TrackKind
import com.dev.Tvivo.playback.TrackSelectionPolicy
import com.dev.Tvivo.ui.adaptive.AdaptiveEnvironment
import com.dev.Tvivo.ui.ThemeMode
import com.dev.Tvivo.playback.PlayerPreferencesStore
import com.dev.Tvivo.ui.theme.TvivoTheme
import com.dev.Tvivo.ui.theme.DefaultAppColors
import com.dev.Tvivo.ui.theme.LightAppColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import java.util.UUID
import kotlin.math.roundToInt

/** Render surface and controls. Playback continues through the service when this Activity stops. */
class PlayerActivity : ComponentActivity() {
    private lateinit var initialThemeMode: ThemeMode
    private var player: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var stopResultFuture: ListenableFuture<*>? = null
    private var pendingStart = false
    private var replayStarting = false
    private var queuedReplay: ReplayRequest? = null
    private var stopPending = false
    private var playerView: PlayerView? = null
    private var composeView: ComposeView? = null
    private var pipWindowOpen = false
    private var pipExitPending = false
    private var dismissingPausedPip = false
    private var pipReceiverRegistered = false
    private var videoAspectRatio = Rational(16, 9)
    private var lastPipEntry: PipPolicy.Entry? = null
    private var lastPipPlaying: Boolean? = null
    private var lastPipAspectRatio: Rational? = null
    private var positionTicker: Job? = null
    private var autoHideJob: Job? = null
    private var feedbackJob: Job? = null
    private var itemId: String? = null
    private var contentType: String? = null
    private var isLive = false
    private var visibility by mutableStateOf(OverlayVisibility())
    private var overlayState by mutableStateOf(PlayerOverlayState("Now playing", false))
    private var audioTracks = emptyList<TrackRef>()
    private var subtitleTracks = emptyList<TrackRef>()
    private var lastHeldSeekAt = 0L
    private var timerUiPinned = false
    private var openMenus by mutableStateOf(emptySet<PlayerPopup>())
    private var dismissMenus by androidx.compose.runtime.mutableIntStateOf(0)
    private var lastDpadAtMs = Long.MIN_VALUE
    private var statusRequest = 0L
    private val sessionExit = PlayerSessionExitController(
        endedSnapshot = {
            val requestId = SleepTimerEndedSnapshot.requestId
            val itemId = SleepTimerEndedSnapshot.itemId
            if (requestId != null && itemId != null)
                EndedItemSnapshot(requestId, SleepTimerEndedSnapshot.title.orEmpty(), itemId)
            else null
        },
        requestPlay = ::startReplay,
    )

    private data class TrackRef(val group: Tracks.Group, val index: Int)

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> Unit
                Player.STATE_READY -> Unit
                Player.STATE_ENDED -> Unit
            }
            updateControls()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateControls()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateControls()
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                    .takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 16f / 9f
                videoAspectRatio = Rational((ratio * 1000).roundToInt().coerceAtLeast(1), 1000)
                updatePipParams()
            }
        }
        override fun onTracksChanged(tracks: Tracks) { updateTracks(tracks); updateControls() }
        override fun onPlayerError(error: PlaybackException) { updateControls() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearance = PlayerPreferencesStore(applicationContext)
        initialThemeMode = runBlocking { appearance.themeMode.first() }
        appearance.syncSystemTheme(initialThemeMode)
        val systemBarColor = (if (initialThemeMode == ThemeMode.Light) LightAppColors else DefaultAppColors).bg.toArgb()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(systemBarColor))
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = initialThemeMode == ThemeMode.Light
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = initialThemeMode == ThemeMode.Light
        val reopensSession = intent.getBooleanExtra(EXTRA_REOPEN_SESSION, false)
        if (!reopensSession && intent.getStringExtra(EXTRA_URL) == null) { finish(); return }
        if (!reopensSession && intent.getStringExtra(EXTRA_REQUEST_ID) == null)
            intent.putExtra(EXTRA_REQUEST_ID, UUID.randomUUID().toString())
        pendingStart = savedInstanceState == null && !reopensSession
        isLive = intent.getBooleanExtra(EXTRA_IS_LIVE, false)
        val resumeFromMs = intent.getLongExtra(EXTRA_RESUME_FROM_MS, 0L)
        itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE)
        overlayState = PlayerOverlayState(
            intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Now playing" }, isLive,
            hasResume = resumeFromMs > 0
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            if (android.os.Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()

        val view = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            useController = false
        }
        playerView = view
        val root = FrameLayout(this).apply { addView(view) }
        val compose = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val themeMode by PlayerPreferencesStore(this@PlayerActivity).themeMode.collectAsState(initial = initialThemeMode)
                SideEffect {
                    val color = (if (themeMode == ThemeMode.Light) LightAppColors else DefaultAppColors).bg.toArgb()
                    window.statusBarColor = color
                    window.navigationBarColor = color
                    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = themeMode == ThemeMode.Light
                    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = themeMode == ThemeMode.Light
                }
                TvivoTheme(mode = themeMode) { AdaptiveEnvironment {
                    PlayerOverlay(
                        state = overlayState,
                        visible = this@PlayerActivity.visibility.visible,
                        onToggleOverlay = { if (this@PlayerActivity.visibility.visible) hideControls() else showControls() },
                        onBack = { stopAndFinish() },
                        onPlayPause = {
                            player?.let { if (it.isPlaying) it.pause() else it.play() }
                            updateControls()
                        },
                        onSeek = { seekBy(it) },
                        onScrub = { seekTo(it) },
                        onAudio = { selectTrack(C.TRACK_TYPE_AUDIO, it) },
                        onSubtitle = { selectTrack(C.TRACK_TYPE_TEXT, it) },
                        onNext = { playNextEpisode() },
                        onStartOver = {
                            seekTo(0L)
                            player?.play()
                            overlayState = overlayState.copy(hasResume = false)
                        },
                        onInput = { rearmControls() },
                        onSeekKey = { direction, event -> seekOnKey(direction, event) },
                        onRetry = { player?.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.RETRY), Bundle.EMPTY); showControls() },
                        onCancelNext = { player?.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.CANCEL_NEXT), Bundle.EMPTY) },
                        onTimerCommand = ::dispatchTimerCommand,
                        onTimerMenuPinned = { pinned ->
                            timerUiPinned = pinned
                            if (this@PlayerActivity.visibility.visible) rearmControls()
                        },
                        dismissMenus = dismissMenus,
                        onMenuOpenChanged = { menu, open ->
                            openMenus = if (open) openMenus + menu else openMenus - menu
                            if (this@PlayerActivity.visibility.visible) rearmControls()
                        },
                        onEndedWatch = ::watchEndedItem,
                    )
                } }
            }
        }
        composeView = compose
        root.addView(compose)
        setContentView(root)
        ContextCompat.registerReceiver(this, pipActionReceiver, IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_CLOSE)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        pipReceiverRegistered = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (PlayerBackPolicy.action(openMenus.isNotEmpty()) == PlayerBackAction.DismissMenu) {
                    dismissMenus++
                    openMenus = emptySet()
                    rearmControls()
                } else stopAndFinish()
            }
        })
        connectController()
        updatePipParams()
        showControls()
        startPositionTicker()
    }

    private fun connectController() {
        val future = MediaController.Builder(applicationContext, SessionToken(applicationContext, ComponentName(applicationContext, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    handleControllerLoss(controller)
                }

                override fun onCustomCommand(
                    controller: MediaController, command: SessionCommand, args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (command.customAction == PlaybackCommands.TIMER_ENDED && player === controller) {
                        val ended = sessionExit.disconnected(intent.getStringExtra(EXTRA_REQUEST_ID))
                        if (ended is PlayerSessionExitState.EndedView) showEndedView(ended.item)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (isDestroyed || controllerFuture !== future) return@addListener
            val connected = runCatching { future.get() }.getOrElse {
                if (stopPending) finish() else handleControllerLoss()
                return@addListener
            }
            player = connected
            connected.addListener(playerListener)
            playerView?.player = connected
            if (stopPending) requestStopAndFinish() else if (pendingStart) {
                sendPlayRequest()
            } else verifyExistingSession(connected)
            updateTracks(connected.currentTracks)
            updateControls()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleControllerLoss(disconnected: MediaController? = null) {
        if (disconnected != null && player !== disconnected) return
        if (isDestroyed || isFinishing || stopPending) return
        playerView?.player = null
        player?.removeListener(playerListener)
        player = null
        statusRequest++ // Invalidate STATUS replies from the released session.
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        when (val exit = sessionExit.disconnected(intent.getStringExtra(EXTRA_REQUEST_ID))) {
            is PlayerSessionExitState.EndedView -> showEndedView(exit.item)
            PlayerSessionExitState.Stopped -> {
                timerUiPinned = true
                overlayState = overlayState.copy(
                    isPlaying = false, seekable = false, hasNext = false, nextPrompt = false,
                    playbackState = "stopped", sleepTimer = SleepTimerUiState(),
                )
                showControls()
            }
        }
        queuedReplay?.let { replay ->
            queuedReplay = null
            startReplay(replay)
        }
    }

    private fun showEndedView(ended: EndedItemSnapshot) {
        timerUiPinned = true
        overlayState = overlayState.copy(
            title = ended.title.ifBlank { overlayState.title }, isPlaying = false,
            seekable = false, hasNext = false, nextPrompt = false,
            playbackState = "sleep_timer_ended",
            sleepTimer = SleepTimerUiState(
                state = SleepTimerUiState.State.Ended,
                endedTitle = ended.title.ifBlank { overlayState.title }, endedItemId = ended.itemId,
            ),
        )
        showControls()
    }

    private fun verifyExistingSession(controller: MediaController) {
        val expected = intent.getStringExtra(EXTRA_REQUEST_ID)
        val future = controller.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.STATUS), Bundle.EMPTY)
        future.addListener({
            if (player !== controller || overlayState.sleepTimer.isEnded) return@addListener
            val extras = runCatching { future.get().extras }.getOrNull() ?: return@addListener
            val active = extras.getString(PlaybackCommands.REQUEST_ID)
            val activeItem = extras.getString(PlaybackCommands.ITEM_ID)
            val queuedItem = activeItem != null && intent.getStringArrayListExtra(EXTRA_EPISODE_IDS)?.contains(activeItem) == true
            if (!stopPending && !isFinishing && expected != null && intent.getStringExtra(EXTRA_REQUEST_ID) == expected && active != expected && !queuedItem)
                finish()
            updateControls()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendPlayRequest() {
        val item = PlaybackHandoff.Item(
            intent.getStringExtra(EXTRA_REQUEST_ID) ?: return,
            intent.getStringExtra(EXTRA_URL) ?: return,
            intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Now playing" },
            intent.getStringExtra(EXTRA_ACCOUNT_ID), intent.getStringExtra(EXTRA_CONTENT_TYPE),
            intent.getStringExtra(EXTRA_ITEM_ID), intent.getBooleanExtra(EXTRA_IS_LIVE, false),
            intent.getLongExtra(EXTRA_RESUME_FROM_MS, 0L),
            playlist = run {
                val ids = intent.getStringArrayListExtra(EXTRA_EPISODE_IDS).orEmpty()
                val urls = intent.getStringArrayListExtra(EXTRA_EPISODE_URLS).orEmpty()
                val titles = intent.getStringArrayListExtra(EXTRA_EPISODE_TITLES).orEmpty()
                ids.mapIndexedNotNull { index, id ->
                    if (index in urls.indices && index in titles.indices) PlaybackHandoff.QueueItem(id, urls[index], titles[index]) else null
                }
            },
            playlistIndex = intent.getIntExtra(EXTRA_EPISODE_INDEX, -1),
        )
        val controller = player ?: return
        val result = controller.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.PLAY), PlaybackCommands.bundle(item))
        pendingStart = false
        if (replayStarting) result.addListener({
            if (player === controller && intent.getStringExtra(EXTRA_REQUEST_ID) == item.requestId &&
                runCatching { result.get().resultCode }.getOrNull() == SessionResult.RESULT_SUCCESS) {
                queuedReplay = null
                replayStarting = false
                updateControls()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopAndFinish() {
        if (stopPending) return
        stopPending = true
        pendingStart = false
        updatePipParams()
        if (player != null) requestStopAndFinish() else finish()
    }

    private fun requestStopAndFinish() {
        val future = player?.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.STOP), Bundle.EMPTY)
        if (future == null) { finish(); return }
        stopResultFuture = future
        // Keep the previous screen covered until Room has committed the final position.
        future.addListener({ finish() }, ContextCompat.getMainExecutor(this))
    }

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT < 26 || stopPending) return
            when (intent.action) {
                ACTION_PIP_PLAY_PAUSE -> if (isInPictureInPictureMode) player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                ACTION_PIP_CLOSE -> if (pipWindowOpen &&
                    PipPolicy.exit(true, player?.playWhenReady == true, explicitClose = true) ==
                    PipPolicy.Exit.StopPlayback) stopAndFinish()
            }
        }
    }

    private fun pipFormFactor(): PipPolicy.FormFactor {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (mode == Configuration.UI_MODE_TYPE_TELEVISION ||
            (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        ) return PipPolicy.FormFactor.Television
        return if (mode == Configuration.UI_MODE_TYPE_NORMAL) PipPolicy.FormFactor.PhoneTablet
            else PipPolicy.FormFactor.Other
    }

    private fun pipEntry(): PipPolicy.Entry = PipPolicy.entry(
        pipFormFactor(), player?.isPlaying == true,
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),
        Build.VERSION.SDK_INT, stopPending || isFinishing,
    )

    private fun pipAction(action: String, title: String, iconId: Int, requestCode: Int): RemoteAction {
        val pending = PendingIntent.getBroadcast(
            this, requestCode, Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(this, iconId), title, title, pending)
    }

    private fun pipParams(entry: PipPolicy.Entry): PictureInPictureParams {
        val isPlaying = player?.isPlaying == true
        val toggle = pipAction(
            ACTION_PIP_PLAY_PAUSE, if (isPlaying) "Pause" else "Play",
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, 1,
        )
        val close = pipAction(ACTION_PIP_CLOSE, "Close", android.R.drawable.ic_menu_close_clear_cancel, 2)
        return PictureInPictureParams.Builder()
            .setAspectRatio(videoAspectRatio)
            .setActions(listOf(toggle, close))
            .apply {
                if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(entry == PipPolicy.Entry.AutoEnter)
                if (Build.VERSION.SDK_INT >= 33) setCloseAction(close)
            }
            .build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < 26 ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val entry = pipEntry()
        val playing = player?.isPlaying == true
        if (entry == lastPipEntry && playing == lastPipPlaying && videoAspectRatio == lastPipAspectRatio) return
        setPictureInPictureParams(pipParams(entry))
        lastPipEntry = entry
        lastPipPlaying = playing
        lastPipAspectRatio = videoAspectRatio
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode &&
            pipEntry() == PipPolicy.Entry.OnUserLeaveHint) {
            enterPictureInPictureMode(pipParams(PipPolicy.Entry.OnUserLeaveHint))
        }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (inPip) {
            pipWindowOpen = true
            pipExitPending = false
            hideControls()
        } else if (pipWindowOpen) {
            // Expansion has window focus; a closed PiP goes to Stop without it.
            pipExitPending = !window.decorView.hasWindowFocus()
            if (!pipExitPending) pipWindowOpen = false
        }
        composeView?.visibility = if (inPip) View.GONE else View.VISIBLE
        if (!inPip && !isFinishing && !stopPending && !dismissingPausedPip) {
            playerView?.player = player
            hideSystemBars()
            showControls()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePipParams()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode && !isFinishing &&
            !stopPending && !dismissingPausedPip) {
            pipExitPending = false
            pipWindowOpen = false
            composeView?.visibility = View.VISIBLE
        }
        if (player == null && controllerFuture == null && !pendingStart && !stopPending && !isFinishing)
            handleControllerLoss()
        updateControls()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode && pipWindowOpen) {
            pipExitPending = false
            pipWindowOpen = false
        }
        if (hasFocus) {
            hideSystemBars()
            updateControls()
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (visibility.visible) rearmControls()
    }

    private fun showControls() {
        visibility = visibility.onInput()
        rearmControls()
        updateControls()
    }

    private fun rearmControls() {
        if (!visibility.visible) return
        visibility = visibility.onInput()
        autoHideJob?.cancel()
        autoHideJob = lifecycleScope.launch {
            delay(CONTROLS_TIMEOUT_MS)
            while (visibility.visible) {
                val elapsed = if (lastDpadAtMs == Long.MIN_VALUE) Long.MAX_VALUE
                    else android.os.SystemClock.elapsedRealtime() - lastDpadAtMs
                val waitMs = PlayerOverlayLogic.autoHideWaitMs(elapsed, DPAD_IDLE_MS)
                if (waitMs > 0L) delay(waitMs)
                else break
            }
            if (openMenus.isEmpty() && !timerUiPinned && overlayState.playbackState != "retry" &&
                overlayState.playbackState != "stopped" &&
                !overlayState.sleepTimer.isWarning && !overlayState.sleepTimer.isEnded)
                visibility = visibility.onTimeout()
        }
    }

    private fun hideControls() {
        autoHideJob?.cancel()
        dismissMenus++
        openMenus = emptySet()
        timerUiPinned = false
        visibility = visibility.onBack()
    }

    private fun updateControls() {
        val exo = player ?: return
        overlayState = overlayState.copy(
            title = exo.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty().ifBlank { overlayState.title },
            isPlaying = exo.isPlaying,
            seekable = !isLive && exo.isCurrentMediaItemSeekable,
            positionMs = exo.currentPosition.coerceAtLeast(0L),
            durationMs = exo.duration.takeIf { it > 0 } ?: 0L,
            hasNext = nextIndex() != null,
        )
        val controller = player
        if (controller != null) {
            val request = ++statusRequest
            val status = controller.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.STATUS), Bundle.EMPTY)
            status.addListener({
                if (request != statusRequest || stopPending || replayStarting || player !== controller) return@addListener
                val extras = runCatching { status.get().extras }.getOrNull() ?: return@addListener
                val activeId = extras.getString(PlaybackCommands.REQUEST_ID)
                val activeTitle = extras.getString(PlaybackCommands.TITLE)
                if (activeId != null) intent.putExtra(EXTRA_REQUEST_ID, activeId)
                if (activeTitle != null) {
                    intent.putExtra(EXTRA_TITLE, activeTitle)
                    overlayState = overlayState.copy(title = activeTitle)
                }
                extras.getString(PlaybackCommands.ITEM_ID)?.let { id ->
                    itemId = id
                    intent.putExtra(EXTRA_ITEM_ID, id)
                }
                val index = extras.getInt("queue_index", -1)
                if (index >= 0) intent.putExtra(EXTRA_EPISODE_INDEX, index)
                val newState = extras.getString(PlaybackCommands.STATE) ?: "playing"
                val prompt = extras.getBoolean(PlaybackCommands.NEXT_PROMPT)
                val title = extras.getString(PlaybackCommands.NEXT_TITLE)
                val endedTitle = extras.getString(PlaybackCommands.TITLE)
                    ?: overlayState.sleepTimer.endedTitle ?: overlayState.title
                val timerUi = SleepTimerUiState.fromPayload(
                    extras.getString(PlaybackCommands.TIMER_STATE)
                        ?: if (newState == "sleep_timer_ended") "Ended" else null,
                    extras.getString(PlaybackCommands.TIMER_MODE),
                    extras.getLong(PlaybackCommands.TIMER_REMAINING_MS, -1L),
                    endedTitle,
                    extras.getString(PlaybackCommands.TIMER_ENDED_ITEM_ID),
                )
                if (overlayState.sleepTimer.isEnded && !timerUi.isEnded) return@addListener
                if (overlayState.playbackState != newState || overlayState.nextPrompt != prompt ||
                    overlayState.nextTitle != title || overlayState.sleepTimer != timerUi
                ) {
                    if ((overlayState.sleepTimer.isWarning && !timerUi.isWarning && !timerUi.isEnded) ||
                        (overlayState.playbackState == "retry" && newState != "retry" &&
                            !timerUi.isWarning && !timerUi.isEnded))
                        timerUiPinned = false
                    overlayState = overlayState.copy(
                        playbackState = newState, nextPrompt = prompt, nextTitle = title,
                        sleepTimer = timerUi,
                    )
                    if (newState == "retry" || timerUi.isWarning || timerUi.isEnded) {
                        timerUiPinned = true
                        visibility = visibility.onInput()
                        rearmControls()
                    }
                }
            }, ContextCompat.getMainExecutor(this))
        }
        updatePipParams()
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode && !stopPending &&
            !isFinishing && exo.playbackState != Player.STATE_ENDED &&
            PipPolicy.exit(true, exo.playWhenReady, explicitClose = false) == PipPolicy.Exit.DismissPaused
        ) {
            dismissingPausedPip = true
            finish()
        }
    }

    private fun dispatchTimerCommand(command: SleepTimerUiCommand) {
        val action = SleepTimerUiPolicy.actionFor(command) ?: return
        val args = Bundle().apply { command.minutes?.let { putInt(PlaybackCommands.TIMER_MINUTES, it) } }
        val result = player?.sendCustomCommand(PlaybackCommands.command(action), args)
        result?.addListener({ updateControls() }, ContextCompat.getMainExecutor(this))
        showControls()
    }

    private fun watchEndedItem() {
        val endedId = overlayState.sleepTimer.endedItemId ?: intent.getStringExtra(EXTRA_ITEM_ID)
        val queueIndex = intent.getStringArrayListExtra(EXTRA_EPISODE_IDS)?.indexOf(endedId) ?: -1
        val url = if (queueIndex >= 0)
            intent.getStringArrayListExtra(EXTRA_EPISODE_URLS)?.getOrNull(queueIndex)
        else intent.getStringExtra(EXTRA_URL)
        val replay = ReplayItem(
            url ?: return,
            overlayState.sleepTimer.endedTitle ?: intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            endedId,
        )
        sessionExit.watchAgain(replay, UUID.randomUUID().toString())
    }

    private fun startReplay(replay: ReplayRequest) {
        intent.putExtra(EXTRA_URL, replay.item.url)
            .putExtra(EXTRA_TITLE, replay.item.title)
            .putExtra(EXTRA_ITEM_ID, replay.item.itemId)
            .putExtra(EXTRA_RESUME_FROM_MS, replay.positionMs)
            .putExtra(EXTRA_REQUEST_ID, replay.requestId)
        val queueIndex = intent.getStringArrayListExtra(EXTRA_EPISODE_IDS)?.indexOf(replay.item.itemId) ?: -1
        if (queueIndex >= 0) intent.putExtra(EXTRA_EPISODE_INDEX, queueIndex)
        itemId = replay.item.itemId
        pendingStart = true
        replayStarting = true
        overlayState = overlayState.copy(
            title = replay.item.title, isPlaying = false, seekable = false,
            sleepTimer = SleepTimerUiState(), playbackState = "playing", positionMs = 0L,
            durationMs = 0L, hasResume = false,
        )
        timerUiPinned = false
        if (player != null) {
            // An idle replacement session can accept PLAY immediately. If the old session is
            // still stopping, its disconnect will retry this request on a fresh session.
            queuedReplay = replay
            sendPlayRequest()
        } else {
            queuedReplay = null
            connectController()
        }
        showControls()
    }

    private fun updateTracks(tracks: Tracks) {
        fun collect(type: Int) = tracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { TrackRef(group, it) }
        }
        audioTracks = collect(C.TRACK_TYPE_AUDIO)
        subtitleTracks = collect(C.TRACK_TYPE_TEXT)
        fun choices(refs: List<TrackRef>, kind: TrackKind) = refs.mapIndexed { ordinal, ref ->
            val format = ref.group.getTrackFormat(ref.index)
            TrackChoice(
                TrackSelectionPolicy.getDisplayName(kind, format.label, format.language, ordinal + 1),
                ref.group.isTrackSelected(ref.index)
            )
        }
        val textDisabled = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        overlayState = overlayState.copy(
            audio = choices(audioTracks, TrackKind.Audio),
            subtitles = if (subtitleTracks.isEmpty()) emptyList() else
                listOf(TrackChoice("Off", textDisabled || subtitleTracks.none { it.group.isTrackSelected(it.index) })) +
                    choices(subtitleTracks, TrackKind.Subtitle)
        )
    }

    private fun selectTrack(type: Int, choice: Int) {
        val exo = player ?: return
        val mediaId = exo.currentMediaItem?.mediaId ?: return
        val choices = if (type == C.TRACK_TYPE_TEXT) overlayState.subtitles else overlayState.audio
        if (choice !in choices.indices) return
        // The service owns ExoPlayer and its TrackGroups. Do not parcel a controller-side
        // TrackGroup back as an override; send the displayed ordinal for local resolution.
        val args = Bundle().apply {
            putInt(PlaybackCommands.TRACK_TYPE, type)
            putInt(PlaybackCommands.TRACK_CHOICE, choice)
            putString(PlaybackCommands.MEDIA_ID, mediaId)
        }
        val result = exo.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.SELECT_TRACK), args)
        val selected = choices.mapIndexed { index, track -> track.copy(selected = index == choice) }
        overlayState = if (type == C.TRACK_TYPE_TEXT) overlayState.copy(subtitles = selected)
            else overlayState.copy(audio = selected)
        result.addListener({
            if (runCatching { result.get().resultCode }.getOrNull() != androidx.media3.session.SessionResult.RESULT_SUCCESS)
                updateTracks(exo.currentTracks)
        }, ContextCompat.getMainExecutor(this))
        rearmControls()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER) {
            lastDpadAtMs = android.os.SystemClock.elapsedRealtime()
            if (visibility.visible) rearmControls()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun seekTo(positionMs: Long) {
        val exo = player ?: return
        if (isLive || !exo.isCurrentMediaItemSeekable) return
        exo.seekTo(positionMs.coerceIn(0L, exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        updateControls()
        rearmControls()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        if (isLive || !exo.isCurrentMediaItemSeekable || deltaMs == 0L) return
        seekTo(exo.currentPosition + deltaMs)
        overlayState = overlayState.copy(seekFeedback = PlayerOverlayLogic.seekLabel(deltaMs))
        feedbackJob?.cancel()
        feedbackJob = lifecycleScope.launch { delay(SEEK_FEEDBACK_TIMEOUT_MS); overlayState = overlayState.copy(seekFeedback = null) }
    }

    private fun seekOnKey(direction: Int, event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val step = if (event.repeatCount == 0) SEEK_INCREMENT_MS else
            HeldSeekAccelerator.stepMilliseconds(event.eventTime - event.downTime)
        if (step == 0L) return
        if (event.repeatCount > 0 && event.eventTime - lastHeldSeekAt < HELD_SEEK_INTERVAL_MS) return
        lastHeldSeekAt = event.eventTime
        seekBy(step * direction)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Dedicated remote/headset media keys work whether or not the overlay is showing.
        if (event != null && event.repeatCount == 0) {
            val exo = player
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { exo?.let { if (it.isPlaying) it.pause() else it.play() }; showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { exo?.play(); showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo?.pause(); showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (nextIndex() != null) { playNextEpisode(); return true } }
            }
        }
        if (event != null && !visibility.visible) {
            when (keyCode) {
                // Down (or Up) on a playing stream brings the controls up, like OK.
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> { showControls(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekOnKey(1, event); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekOnKey(-1, event); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun nextIndex(): Int? {
        if (isLive || contentType != "series") return null
        val ids = intent.getStringArrayListExtra(EXTRA_EPISODE_IDS) ?: return null
        val current = intent.getIntExtra(EXTRA_EPISODE_INDEX, -1)
        return (current + 1).takeIf { current >= 0 && it in ids.indices }
    }

    private fun playNextEpisode() {
        val next = nextIndex() ?: return
        val ids = intent.getStringArrayListExtra(EXTRA_EPISODE_IDS) ?: return
        val urls = intent.getStringArrayListExtra(EXTRA_EPISODE_URLS) ?: return
        val titles = intent.getStringArrayListExtra(EXTRA_EPISODE_TITLES) ?: return
        if (next !in urls.indices || next !in titles.indices) return
        intent.putExtra(EXTRA_URL, urls[next])
            .putExtra(EXTRA_TITLE, titles[next])
            .putExtra(EXTRA_ITEM_ID, ids[next])
            .putExtra(EXTRA_EPISODE_INDEX, next)
            .putExtra(EXTRA_RESUME_FROM_MS, 0L)
            .putExtra(EXTRA_REQUEST_ID, UUID.randomUUID().toString())
        itemId = ids[next]
        overlayState = overlayState.copy(title = titles[next], hasResume = false, positionMs = 0L)
        sendPlayRequest()
        showControls()
    }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = lifecycleScope.launch {
            while (isActive) {
                delay(when {
                    visibility.visible -> POSITION_TICK_INTERVAL_MS
                    overlayState.sleepTimer.isActive -> HIDDEN_TIMER_POLL_INTERVAL_MS
                    else -> HIDDEN_STATUS_POLL_INTERVAL_MS
                })
                updateControls()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        playerView?.player = player
    }

    override fun onStop() {
        playerView?.player = null
        if (PipPolicy.shouldStopAfterExit(pipExitPending, isChangingConfigurations,
                dismissingPausedPip, stopPending)) {
            // PiP close exits into Stop; expansion reaches onResume and clears the pending exit.
            pipExitPending = false
            pipWindowOpen = false
            stopResultFuture = player?.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.STOP), Bundle.EMPTY)
        }
        super.onStop()
    }

    override fun onDestroy() {
        positionTicker?.cancel()
        autoHideJob?.cancel(); feedbackJob?.cancel()
        // On API 26-32 the system close affordance has no custom close callback.
        if (pipWindowOpen && isFinishing && !dismissingPausedPip && !stopPending &&
            player?.playbackState != Player.STATE_ENDED
        ) stopResultFuture = player?.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.STOP), Bundle.EMPTY)
        if (pipReceiverRegistered) unregisterReceiver(pipActionReceiver)
        playerView?.player = null
        playerView = null
        composeView = null
        player?.removeListener(playerListener)
        player = null
        val connection = controllerFuture
        val stopping = stopResultFuture
        if (connection != null && stopping != null) {
            // Keep the controller connected until the service has received Stop.
            var released = false
            val release = {
                if (!released) {
                    released = true
                    MediaController.releaseFuture(connection)
                }
            }
            stopping.addListener({ release() }, ContextCompat.getMainExecutor(this))
            android.os.Handler(mainLooper).postDelayed({ release() }, 1_000L)
        } else connection?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        if (newIntent.getBooleanExtra(EXTRA_REOPEN_SESSION, false)) {
            player?.let(::verifyExistingSession)
            showControls()
            return
        }
        setIntent(newIntent)
        if (newIntent.getStringExtra(EXTRA_REQUEST_ID) == null)
            newIntent.putExtra(EXTRA_REQUEST_ID, UUID.randomUUID().toString())
        isLive = newIntent.getBooleanExtra(EXTRA_IS_LIVE, false)
        itemId = newIntent.getStringExtra(EXTRA_ITEM_ID)
        contentType = newIntent.getStringExtra(EXTRA_CONTENT_TYPE)
        overlayState = PlayerOverlayState(newIntent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Now playing" }, isLive)
        pendingStart = true
        replayStarting = false
        if (player != null) {
            sendPlayRequest()
        } else if (controllerFuture == null) connectController()
        showControls()
        updatePipParams()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_IS_LIVE = "is_live"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_RESUME_FROM_MS = "resume_from_ms"
        const val EXTRA_TITLE = "title"
        const val EXTRA_EPISODE_IDS = "episode_ids"
        const val EXTRA_EPISODE_URLS = "episode_urls"
        const val EXTRA_EPISODE_TITLES = "episode_titles"
        const val EXTRA_EPISODE_INDEX = "episode_index"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_REOPEN_SESSION = "reopen_session"
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val HELD_SEEK_INTERVAL_MS = 300L
        private const val POSITION_TICK_INTERVAL_MS = 1_000L
        private const val HIDDEN_TIMER_POLL_INTERVAL_MS = 15_000L
        private const val HIDDEN_STATUS_POLL_INTERVAL_MS = 30_000L
        private const val CONTROLS_TIMEOUT_MS = 5_000L
        private const val DPAD_IDLE_MS = 8_000L
        private const val SEEK_FEEDBACK_TIMEOUT_MS = 1_000L
        private const val ACTION_PIP_PLAY_PAUSE = "com.dev.Tvivo.player.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_CLOSE = "com.dev.Tvivo.player.PIP_CLOSE"
    }
}
