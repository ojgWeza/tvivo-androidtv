package com.dev.Tvivo.ui.player

import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
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
import com.dev.Tvivo.data.StreamUrlBuilder
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        val isLive = intent.getBooleanExtra(EXTRA_IS_LIVE, false)
        if (url == null) {
            finish()
            return
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
        setContentView(playerView)

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
        exo.playWhenReady = true

        startFirstFrameWatchdog()
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
        Log.w(TAG, "playback failed: $error ${cause?.message?.let(StreamUrlBuilder::redact) ?: ""}")
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onStop() {
        super.onStop()
        release()
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
    }

    private fun release() {
        firstFrameWatchdog?.cancel()
        bufferingWatchdog?.cancel()
        player?.release()
        player = null
    }

    companion object {
        private const val TAG = "TvivoPlayer"
        const val EXTRA_URL = "url"
        const val EXTRA_IS_LIVE = "is_live"

        /** A progressive `.mkv` can legitimately take 3–10 s to open. */
        private const val FIRST_FRAME_TIMEOUT_MS = 20_000L
        private const val BUFFERING_TIMEOUT_MS = 25_000L
    }
}
