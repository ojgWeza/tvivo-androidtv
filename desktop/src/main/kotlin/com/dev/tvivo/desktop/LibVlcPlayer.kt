package com.dev.tvivo.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Canvas
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class LibVlcPlayer(
    private val onState: (String) -> Unit,
) : AutoCloseable {
    private var api: LibVlc? = null
    private var instance: Pointer? = null
    private var mediaPlayer: Pointer? = null
    private var eventManager: Pointer? = null
    private var eventCallback: LibVlcEventCallback? = null
    private val closed = AtomicBoolean(false)

    fun initialise(surface: Canvas): Result<Unit> = runCatching {
        check(!closed.get()) { "Player has already been closed." }
        val libVlcDirectory = locateLibVlc()
        NativeLibraryPath.configure(libVlcDirectory)
        api = Native.load("libvlc", LibVlc::class.java)
        val loadedApi = checkNotNull(api)
        val createdInstance = checkNotNull(loadedApi.libvlc_new(0, null)) { "libvlc_new returned null." }
        instance = createdInstance
        val createdPlayer = checkNotNull(loadedApi.libvlc_media_player_new(createdInstance)) {
            "libvlc_media_player_new returned null."
        }
        mediaPlayer = createdPlayer
        loadedApi.libvlc_media_player_set_hwnd(createdPlayer, Native.getComponentPointer(surface))
        eventManager = loadedApi.libvlc_media_player_event_manager(createdPlayer)
        eventCallback = LibVlcEventCallback { event, _ ->
            onState(eventName(event?.getInt(0) ?: -1))
        }.also { callback ->
            eventManager?.let { loadedApi.libvlc_event_attach(it, MediaEndReached, callback, null) }
            eventManager?.let { loadedApi.libvlc_event_attach(it, MediaEncounteredError, callback, null) }
        }
        onState("Ready")
    }

    fun play(file: File): Result<Unit> = runCatching {
        require(file.isFile) { "Choose an existing local media file." }
        require(file.extension.lowercase() in supportedExtensions) { "Only .mp4, .mkv, and .ts are supported by this POC." }
        val loadedApi = requireApi()
        val loadedInstance = checkNotNull(instance)
        val loadedPlayer = checkNotNull(mediaPlayer)
        loadedApi.libvlc_media_player_stop(loadedPlayer)
        val media = checkNotNull(loadedApi.libvlc_media_new_path(loadedInstance, file.absolutePath)) {
            "libvlc_media_new_path returned null."
        }
        try {
            loadedApi.libvlc_media_player_set_media(loadedPlayer, media)
            check(loadedApi.libvlc_media_player_play(loadedPlayer) == 0) { "LibVLC could not start playback." }
            onState("Playing ${file.extension.lowercase()} fixture")
        } finally {
            loadedApi.libvlc_media_release(media)
        }
    }

    fun pause() = mediaPlayer?.let { requireApi().libvlc_media_player_set_pause(it, 1); onState("Paused") }
    fun resume() = mediaPlayer?.let { requireApi().libvlc_media_player_set_pause(it, 0); onState("Playing") }
    fun stop() = mediaPlayer?.let { requireApi().libvlc_media_player_stop(it); onState("Stopped") }
    fun seek(position: Float) = mediaPlayer?.let { requireApi().libvlc_media_player_set_position(it, position.coerceIn(0f, 1f)); onState("Seek ${(position * 100).toInt()}%") }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mediaPlayer?.let { player ->
            api?.libvlc_media_player_stop(player)
            api?.libvlc_media_player_release(player)
        }
        instance?.let { api?.libvlc_release(it) }
        mediaPlayer = null
        instance = null
        eventManager = null
        eventCallback = null
        onState("Closed")
    }

    private fun requireApi(): LibVlc = checkNotNull(api) { "LibVLC is not initialised." }

    private fun locateLibVlc(): File {
        val configured = System.getProperty("tvivo.libvlc.dir")
            ?: System.getenv("TVIVO_LIBVLC_DIR")
            ?: error("Set -Dtvivo.libvlc.dir or TVIVO_LIBVLC_DIR to your LibVLC directory.")
        return File(configured).also {
            require(it.isDirectory && File(it, "libvlc.dll").isFile) {
                "LibVLC directory must contain libvlc.dll: ${it.absolutePath}"
            }
            require(File(it, "plugins").isDirectory) { "LibVLC plugins directory is missing: ${it.absolutePath}" }
        }
    }

    private fun eventName(type: Int) = when (type) {
        MediaEndReached -> "Ended"
        MediaEncounteredError -> "Playback error"
        else -> "LibVLC event $type"
    }

    private interface LibVlc : Library {
        fun libvlc_new(argc: Int, argv: Array<String>?): Pointer?
        fun libvlc_release(instance: Pointer)
        fun libvlc_media_new_path(instance: Pointer, path: String): Pointer?
        fun libvlc_media_release(media: Pointer)
        fun libvlc_media_player_new(instance: Pointer): Pointer?
        fun libvlc_media_player_release(player: Pointer)
        fun libvlc_media_player_set_media(player: Pointer, media: Pointer)
        fun libvlc_media_player_play(player: Pointer): Int
        fun libvlc_media_player_set_pause(player: Pointer, doPause: Int)
        fun libvlc_media_player_stop(player: Pointer)
        fun libvlc_media_player_set_position(player: Pointer, position: Float)
        fun libvlc_media_player_set_hwnd(player: Pointer, drawable: Pointer)
        fun libvlc_media_player_event_manager(player: Pointer): Pointer?
        fun libvlc_event_attach(manager: Pointer, eventType: Int, callback: LibVlcEventCallback, userData: Pointer?): Int
    }

    private fun interface LibVlcEventCallback : Callback {
        fun invoke(event: Pointer?, userData: Pointer?)
    }

    private object NativeLibraryPath {
        fun configure(directory: File) {
            System.setProperty("jna.library.path", directory.absolutePath)
            System.setProperty("VLC_PLUGIN_PATH", File(directory, "plugins").absolutePath)
        }
    }

    private companion object {
        val supportedExtensions = setOf("mp4", "mkv", "ts")
        const val MediaEndReached = 265
        const val MediaEncounteredError = 266
    }
}
