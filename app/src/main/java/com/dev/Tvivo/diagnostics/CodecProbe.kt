package com.dev.Tvivo.diagnostics

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/**
 * One-time record of what the target box can actually decode, before any stream is
 * opened. `adb shell dumpsys media.codec` is the documented way to do this and it no
 * longer works — the service does not exist on API 34 — so the probe runs in-process
 * against `MediaCodecList`, which has the added advantage of reporting exactly what
 * ExoPlayer will see, on the emulator and on the real TV alike.
 *
 * Read it with: `adb logcat -s TvivoCodecProbe`
 */
object CodecProbe {

    private const val TAG = "TvivoCodecProbe"

    private val INTERESTING = listOf(
        "video/avc",        // H.264 — the bulk of the catalog
        "video/hevc",       // H.265 — the MediaTek black-video risk
        "video/x-vnd.on2.vp9",
        "video/av01",
        "video/mpeg2",
        "audio/mp4a-latm",  // AAC
        "audio/ac3",
        "audio/eac3",
        "audio/mpeg"        // MP3
    )

    fun log() {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        Log.i(TAG, "=== codec probe: ${codecs.size} codecs ===")

        for (mime in INTERESTING) {
            val decoders = codecs.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            if (decoders.isEmpty()) {
                Log.w(TAG, "$mime: NO DECODER")
                continue
            }
            for (info in decoders) {
                Log.i(TAG, "$mime: ${info.name}${hardwareSuffix(info)} ${capabilities(info, mime)}")
            }
        }
        Log.i(TAG, "=== codec probe end ===")
    }

    private fun hardwareSuffix(info: MediaCodecInfo): String =
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            if (info.isHardwareAccelerated) " [hw]" else " [sw]"
        } else {
            ""
        }

    private fun capabilities(info: MediaCodecInfo, mime: String): String = try {
        val caps = info.getCapabilitiesForType(mime)
        caps.videoCapabilities?.let { v ->
            "max=${v.supportedWidths.upper}x${v.supportedHeights.upper}"
        } ?: caps.audioCapabilities?.let { a ->
            "maxChannels=${a.maxInputChannelCount}"
        } ?: ""
    } catch (e: Exception) {
        "caps unavailable"
    }
}
