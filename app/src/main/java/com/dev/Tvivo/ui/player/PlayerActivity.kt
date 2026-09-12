package com.dev.Tvivo.ui.player

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.data.StreamUrlBuilder
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.repository.PlaybackStateRepository
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.ui.common.ErrorCopy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Media3's [PlayerView] with a small, remote-first transport overlay. */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var firstFrameWatchdog: Job? = null
    private var bufferingWatchdog: Job? = null
    private var positionTicker: Job? = null
    private var playbackState: PlaybackStateRepository? = null
    private var itemId: String? = null
    private var contentType: String? = null
    private var playerView: PlayerView? = null
    private var isLive = false
    private var failureShown = false

    private var controls: View? = null
    private var playPauseButton: Button? = null
    private var elapsedText: TextView? = null
    private var durationText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var seekFeedback: TextView? = null
    private var controlsRoot: FrameLayout? = null
    private var autoHideControls: Runnable? = null
    private var hideSeekFeedback: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        isLive = intent.getBooleanExtra(EXTRA_IS_LIVE, false)
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        val resumeFromMs = intent.getLongExtra(EXTRA_RESUME_FROM_MS, 0L)
        itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Now playing" }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (accountId != null && !isLive) playbackState = PlaybackStateRepository(AppDatabase.get(this), accountId)

        val view = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            useController = false
        }
        playerView = view
        val root = FrameLayout(this).apply { addView(view) }
        controlsRoot = root
        buildControls(root, title, resumeFromMs > 0)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (controls?.visibility == View.VISIBLE) hideControls() else finish()
            }
        })

        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            )
            .setMp3ExtractorFlags(androidx.media3.extractor.mp3.Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
        val exo = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
        player = exo
        view.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> startBufferingWatchdog()
                    Player.STATE_READY -> bufferingWatchdog?.cancel()
                    Player.STATE_ENDED -> finish()
                }
                updateControls()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = updateControls()
            override fun onRenderedFirstFrame() { firstFrameWatchdog?.cancel() }
            override fun onPlayerError(error: PlaybackException) = fail(AppError.StreamUnavailable, error)
        })
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        if (resumeFromMs > 0) exo.seekTo(resumeFromMs)
        exo.playWhenReady = true
        showControls()
        startFirstFrameWatchdog()
        startPositionTicker()
    }

    private fun buildControls(root: FrameLayout, title: String, hasResume: Boolean) {
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(32, 0, 0, 0)) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(28), dp(32), 0)
        }
        top.addView(controlButton("Back") { finish() })
        top.addView(TextView(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = 20f; maxLines = 2; setPadding(dp(20), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        overlay.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), 0, dp(32), dp(32))
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        if (hasResume && !isLive) actions.addView(controlButton("Start over") {
            player?.seekTo(0); player?.play(); updateControls()
        })
        playPauseButton = controlButton("Pause") {
            player?.let { if (it.isPlaying) it.pause() else it.play() }; updateControls()
        }
        actions.addView(playPauseButton)
        transport.addView(actions)
        if (!isLive) {
            val progress = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, 0) }
            elapsedText = timeText(); durationText = timeText()
            progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = PROGRESS_MAX }
            progress.addView(elapsedText)
            progress.addView(progressBar, LinearLayout.LayoutParams(0, dp(8), 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
            progress.addView(durationText)
            transport.addView(progress, LinearLayout.LayoutParams(-1, -2))
        }
        overlay.addView(transport, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        seekFeedback = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; setPadding(dp(18), dp(10), dp(18), dp(10))
            background = focusBackground(Color.argb(220, 0, 0, 0), Color.argb(220, 0, 0, 0)); visibility = View.GONE
        }
        overlay.addView(seekFeedback, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        controls = overlay
    }

    private fun controlButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); textSize = 16f
        background = focusBackground(Color.argb(205, 20, 20, 20), FOCUS_COLOR)
        setPadding(dp(18), dp(8), dp(18), dp(8)); setOnClickListener { onClick() }
    }

    private fun focusBackground(normal: Int, focused: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), roundedBackground(focused, FOCUS_COLOR))
        addState(intArrayOf(), roundedBackground(normal, Color.TRANSPARENT))
    }
    private fun roundedBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(4).toFloat(); setColor(fill); setStroke(dp(3), stroke)
    }
    private fun timeText() = TextView(this).apply { text = "0:00"; setTextColor(Color.WHITE); textSize = 14f }

    private fun showControls() {
        controls?.visibility = View.VISIBLE
        autoHideControls?.let { controlsRoot?.removeCallbacks(it) }
        autoHideControls = Runnable { hideControls() }.also { controlsRoot?.postDelayed(it, CONTROLS_TIMEOUT_MS) }
        playPauseButton?.post { playPauseButton?.requestFocus() }
        updateControls()
    }
    private fun hideControls() { controls?.visibility = View.GONE; seekFeedback?.visibility = View.GONE }
    private fun updateControls() {
        val exo = player ?: return
        playPauseButton?.text = if (exo.isPlaying) "Pause" else "Play"
        if (!isLive) {
            val position = exo.currentPosition.coerceAtLeast(0L); val duration = exo.duration.takeIf { it > 0 } ?: 0L
            elapsedText?.text = formatTime(position); durationText?.text = formatTime(duration)
            progressBar?.progress = if (duration == 0L) 0 else ((position * PROGRESS_MAX) / duration).toInt()
        }
    }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = lifecycleScope.launch {
            var ticks = 0
            while (isActive) {
                delay(POSITION_TICK_INTERVAL_MS); updateControls(); ticks++
                if (ticks == (POSITION_SAVE_INTERVAL_MS / POSITION_TICK_INTERVAL_MS).toInt()) { savePosition(); ticks = 0 }
            }
        }
    }
    private suspend fun savePosition() {
        val exo = player ?: return; val repo = playbackState ?: return; val id = itemId ?: return; val type = contentType ?: return
        repo.savePosition(type, id, exo.currentPosition, exo.duration.takeIf { it > 0 } ?: 0L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && controls?.visibility != View.VISIBLE) {
            showControls(); return true
        }
        val exo = player
        if (exo != null && controls?.visibility != View.VISIBLE && !isLive && exo.isCurrentMediaItemSeekable) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(SEEK_INCREMENT_MS, "+10 seconds"); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-SEEK_INCREMENT_MS, "-10 seconds"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    private fun seekBy(deltaMs: Long, feedback: String) {
        val exo = player ?: return; val end = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, end))
        seekFeedback?.text = feedback; seekFeedback?.visibility = View.VISIBLE
        hideSeekFeedback?.let { controlsRoot?.removeCallbacks(it) }
        hideSeekFeedback = Runnable { seekFeedback?.visibility = View.GONE }.also { controlsRoot?.postDelayed(it, SEEK_FEEDBACK_TIMEOUT_MS) }
        showControls()
    }
    private fun startFirstFrameWatchdog() {
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = lifecycleScope.launch { delay(FIRST_FRAME_TIMEOUT_MS); fail(AppError.StreamUnavailable, null) }
    }
    private fun startBufferingWatchdog() {
        bufferingWatchdog?.cancel()
        bufferingWatchdog = lifecycleScope.launch { delay(BUFFERING_TIMEOUT_MS); fail(AppError.ConnectionLimitReached, null) }
    }
    private fun fail(error: AppError, cause: PlaybackException?) {
        DiagnosticLog.error("playback", "$error${cause?.errorCodeName?.let { " ($it)" } ?: ""}")
        Log.w(TAG, "playback failed: $error ${cause?.message?.let(StreamUrlBuilder::redact) ?: ""}")
        if (failureShown || isFinishing) return
        failureShown = true; firstFrameWatchdog?.cancel(); bufferingWatchdog?.cancel(); player?.stop(); setResult(RESULT_CANCELED)
        AlertDialog.Builder(this).setMessage(ErrorCopy.of(error).message).setPositiveButton("Back to list") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
    override fun onStop() {
        val exo = player; val repo = playbackState; val id = itemId; val type = contentType
        val position = exo?.currentPosition; val duration = exo?.duration?.takeIf { it > 0 } ?: 0L
        release(); super.onStop()
        if (exo != null && repo != null && id != null && type != null) kotlinx.coroutines.runBlocking { repo.savePosition(type, id, position ?: 0L, duration) }
    }
    override fun onDestroy() { super.onDestroy(); release() }
    private fun release() {
        firstFrameWatchdog?.cancel(); bufferingWatchdog?.cancel(); positionTicker?.cancel()
        autoHideControls?.let { controlsRoot?.removeCallbacks(it) }
        hideSeekFeedback?.let { controlsRoot?.removeCallbacks(it) }
        val exo = player; player = null; playerView?.player = null; playerView = null; exo?.stop(); exo?.release()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun formatTime(ms: Long): String {
        val seconds = ms / 1_000; val minutes = seconds / 60
        return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60) else "%d:%02d".format(minutes, seconds % 60)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "TvivoPlayer"
        const val EXTRA_URL = "url"
        const val EXTRA_IS_LIVE = "is_live"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_RESUME_FROM_MS = "resume_from_ms"
        const val EXTRA_TITLE = "title"
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val POSITION_TICK_INTERVAL_MS = 1_000L
        private const val POSITION_SAVE_INTERVAL_MS = 10_000L
        private const val FIRST_FRAME_TIMEOUT_MS = 20_000L
        private const val BUFFERING_TIMEOUT_MS = 25_000L
        private const val CONTROLS_TIMEOUT_MS = 5_000L
        private const val SEEK_FEEDBACK_TIMEOUT_MS = 1_000L
        private const val PROGRESS_MAX = 1_000
        private const val FOCUS_COLOR = 0xffff8c00.toInt()
    }
}
