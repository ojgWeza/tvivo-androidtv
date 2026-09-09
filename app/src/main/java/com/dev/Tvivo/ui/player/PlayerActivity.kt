package com.dev.Tvivo.ui.player

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.data.StreamUrlBuilder
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.repository.PlaybackStateRepository
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * Media3's classic `PlayerView` in an `AndroidView` host, not Compose-native and not
 * Leanback (deprecated, along with `LeanbackPlayerAdapter`). Streams are direct
 * progressive files — `.mkv`, `.mp4`, `.ts` — never HLS manifests.
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var firstFrameWatchdog: Job? = null
    private var bufferingWatchdog: Job? = null
    private var positionTicker: Job? = null
    private var playbackState: PlaybackStateRepository? = null
    private var itemId: String? = null
    private var contentType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        val isLive = intent.getBooleanExtra(EXTRA_IS_LIVE, false)
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        val resumeFromMs = intent.getLongExtra(EXTRA_RESUME_FROM_MS, 0L)
        itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE)

        if (url == null) {
            finish()
            return
        }

        // Live is excluded from resume tracking entirely — a live stream has no
        // meaningful resume point.
        if (accountId != null && !isLive) {
            playbackState = PlaybackStateRepository(AppDatabase.get(this), accountId)
        }

        val playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Progressive live streams cannot seek at all.
            setShowNextButton(false)
            setShowPreviousButton(false)
            useController = !isLive
        }

        // Back *works* here — the defect was discoverability, not the mechanism (Q-4).
        // A plain corner label, tied to the same visibility as the controller so it
        // never fights the video for attention once the user has seen it.
        val backHint = TextView(this).apply {
            text = "Back to exit"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(24, 12, 24, 12)
            textSize = 14f
        }
        val root = FrameLayout(this).apply {
            addView(playerView)
            addView(
                backHint,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    topMargin = 32
                    marginStart = 32
                }
            )
        }
        setContentView(root)

        if (isLive) {
            // No controller to piggyback on, so the hint gets its own timeout.
            backHint.postDelayed({ backHint.visibility = android.view.View.GONE }, BACK_HINT_TIMEOUT_MS)
        } else {
            playerView.setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    backHint.visibility = visibility
                }
            )
        }

        // These flags are silent failures, not exceptions. Without them a `.ts` lacking
        // access unit delimiters hangs in BUFFERING forever with no error, and a
        // progressive file has no seek index at all.
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            )
            .setMp3ExtractorFlags(
                androidx.media3.extractor.mp3.Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
            )

        val exo = ExoPlayer.Builder(this)
            .setRenderersFactory(
                DefaultRenderersFactory(this)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .build()

        player = exo
        playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> startBufferingWatchdog()
                    Player.STATE_READY -> {
                        bufferingWatchdog?.cancel()
                    }
                    Player.STATE_ENDED -> finish()
                }
            }

            override fun onRenderedFirstFrame() {
                // HEVC on some MediaTek boxes renders black video with audio playing and
                // no exception at all — a BUFFERING watchdog cannot see that, so first
                // frame gets its own.
                firstFrameWatchdog?.cancel()
            }

            override fun onPlayerError(error: PlaybackException) {
                fail(AppError.StreamUnavailable, error)
            }
        })

        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        if (resumeFromMs > 0) exo.seekTo(resumeFromMs)
        exo.playWhenReady = true

        startFirstFrameWatchdog()
        startPositionTicker()
    }

    /**
     * Position is persisted while playing, not only on exit: the browse Activity and this
     * one can both be killed under memory pressure on a 1-2 GB box, and a resume point
     * that only survives a clean exit is a resume point that mostly does not survive.
     */
    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = lifecycleScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                savePosition()
            }
        }
    }

    private suspend fun savePosition() {
        val exo = player ?: return
        val repo = playbackState ?: return
        val id = itemId ?: return
        val type = contentType ?: return
        val position = exo.currentPosition
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        repo.savePosition(type, id, position, duration)
    }

    private fun startFirstFrameWatchdog() {
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = lifecycleScope.launch {
            delay(FIRST_FRAME_TIMEOUT_MS)
            fail(AppError.StreamUnavailable, null)
        }
    }

    private fun startBufferingWatchdog() {
        bufferingWatchdog?.cancel()
        bufferingWatchdog = lifecycleScope.launch {
            delay(BUFFERING_TIMEOUT_MS)
            // Probable, not definitive: panels signal an exhausted slot inconsistently.
            fail(AppError.ConnectionLimitReached, null)
        }
    }

    private fun fail(error: AppError, cause: PlaybackException?) {
        // ExoPlayer error messages echo the full URI, which carries the account password
        // in the path. Everything logged goes through redact().
        DiagnosticLog.error(
            "playback",
            "$error${cause?.errorCodeName?.let { " ($it)" } ?: ""}"
        )
        Log.w(TAG, "playback failed: $error ${cause?.message?.let(StreamUrlBuilder::redact) ?: ""}")
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onStop() {
        super.onStop()
        val exo = player
        val repo = playbackState
        val id = itemId
        val type = contentType
        if (exo != null && repo != null && id != null && type != null) {
            val position = exo.currentPosition
            val duration = exo.duration.takeIf { it > 0 } ?: 0L
            // The Activity is going away, so this cannot ride on lifecycleScope.
            kotlinx.coroutines.runBlocking { repo.savePosition(type, id, position, duration) }
        }
        release()
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
    }

    private fun release() {
        firstFrameWatchdog?.cancel()
        bufferingWatchdog?.cancel()
        positionTicker?.cancel()
        player?.release()
        player = null
    }

    companion object {
        private const val TAG = "TvivoPlayer"
        const val EXTRA_URL = "url"
        const val EXTRA_IS_LIVE = "is_live"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_RESUME_FROM_MS = "resume_from_ms"

        private const val POSITION_SAVE_INTERVAL_MS = 10_000L

        /** A progressive `.mkv` can legitimately take 3–10 s to open. */
        private const val FIRST_FRAME_TIMEOUT_MS = 20_000L
        private const val BUFFERING_TIMEOUT_MS = 25_000L
        private const val BACK_HINT_TIMEOUT_MS = 4_000L
    }
}
