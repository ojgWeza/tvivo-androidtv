package com.dev.tvivo.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Canvas
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream
import java.util.concurrent.atomic.AtomicBoolean

internal class LibVlcPlayer(
    private val onState: (String) -> Unit,
) : AutoCloseable {
    private var api: LibVlc? = null
    private var instance: Pointer? = null
    private var mediaPlayer: Pointer? = null
    private var eventManager: Pointer? = null
    private var eventCallback: LibVlcEventCallback? = null
    @Volatile private var pendingResumeMs = 0L
    private val closed = AtomicBoolean(false)

    fun initialise(surface: Canvas): Result<Unit> = runCatching {
        check(!closed.get()) { "Player has already been closed." }
        val libVlcDirectory = locateLibVlc()
        NativeLibraryPath.configure(libVlcDirectory)
        api = Native.load("libvlc", LibVlc::class.java)
        val loadedApi = checkNotNull(api)
        val createdInstance = checkNotNull(loadedApi.libvlc_new(1, arrayOf("--plugin-path=${File(libVlcDirectory, "plugins").absolutePath}"))) { "libvlc_new returned null." }
        instance = createdInstance
        val createdPlayer = checkNotNull(loadedApi.libvlc_media_player_new(createdInstance)) {
            "libvlc_media_player_new returned null."
        }
        mediaPlayer = createdPlayer
        loadedApi.libvlc_media_player_set_hwnd(createdPlayer, Native.getComponentPointer(surface))
        eventManager = loadedApi.libvlc_media_player_event_manager(createdPlayer)
        eventCallback = LibVlcEventCallback { event, _ ->
            val type = event?.getInt(0) ?: -1
            if (type == MediaPlayerPlaying && pendingResumeMs > 0L) {
                mediaPlayer?.let { loadedApi.libvlc_media_player_set_time(it, pendingResumeMs) }
                pendingResumeMs = 0L
            }
            onState(eventName(type))
        }.also { callback ->
            eventManager?.let { manager ->
                watchedEvents.forEach { eventType ->
                    check(loadedApi.libvlc_event_attach(manager, eventType, callback, null) == 0) {
                        "LibVLC could not observe playback state."
                    }
                }
            }
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

    /** Plays a user-selected provider URL. Callers must never log this URL: it contains credentials. */
    fun playUrl(url: String, resumeFromMs: Long = 0L): Result<Unit> = runCatching {
        require(url.startsWith("http://") || url.startsWith("https://")) { "Unsupported playback URL." }
        val loadedApi = requireApi()
        val loadedInstance = checkNotNull(instance)
        val loadedPlayer = checkNotNull(mediaPlayer)
        loadedApi.libvlc_media_player_stop(loadedPlayer)
        val media = checkNotNull(loadedApi.libvlc_media_new_location(loadedInstance, url)) {
            "libvlc_media_new_location returned null."
        }
        try {
            loadedApi.libvlc_media_player_set_media(loadedPlayer, media)
            pendingResumeMs = resumeFromMs
            check(loadedApi.libvlc_media_player_play(loadedPlayer) == 0) { "LibVLC could not start playback." }
            onState(if (resumeFromMs > 0L) "Resuming stream…" else "Opening stream…")
        } finally {
            loadedApi.libvlc_media_release(media)
        }
    }

    fun pause() = mediaPlayer?.let { requireApi().libvlc_media_player_set_pause(it, 1); onState("Paused") }
    fun resume() = mediaPlayer?.let { requireApi().libvlc_media_player_set_pause(it, 0); onState("Playing") }
    fun stop() = mediaPlayer?.let { requireApi().libvlc_media_player_stop(it); onState("Stopped") }
    fun seek(position: Float) = mediaPlayer?.let { requireApi().libvlc_media_player_set_position(it, position.coerceIn(0f, 1f)); onState("Seek ${(position * 100).toInt()}%") }
    fun positionMs(): Long = mediaPlayer?.let { requireApi().libvlc_media_player_get_time(it) }?.coerceAtLeast(0L) ?: 0L
    fun durationMs(): Long = mediaPlayer?.let { requireApi().libvlc_media_player_get_length(it) }?.coerceAtLeast(0L) ?: 0L

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
        val configured = sequenceOf(
            System.getProperty("tvivo.libvlc.dir"),
            System.getenv("TVIVO_LIBVLC_DIR"),
            BundledLibVlc.install().absolutePath,
        ).filterNotNull().firstOrNull { File(it, "libvlc.dll").isFile }
            ?: error("The bundled LibVLC runtime could not be prepared. Reinstall Tvivo or set TVIVO_LIBVLC_DIR for development.")
        return File(configured).also {
            require(it.isDirectory && File(it, "libvlc.dll").isFile) {
                "LibVLC directory must contain libvlc.dll: ${it.absolutePath}"
            }
            require(File(it, "plugins").isDirectory) { "LibVLC plugins directory is missing: ${it.absolutePath}" }
        }
    }

    private fun eventName(type: Int) = when (type) {
        MediaPlayerOpening -> "Opening stream…"
        MediaPlayerBuffering -> "Buffering stream…"
        MediaPlayerPlaying -> "Playing"
        MediaPlayerPaused -> "Paused"
        MediaPlayerStopped -> "Stopped"
        MediaEndReached -> "Ended"
        MediaEncounteredError -> "Playback error. Check the stream is reachable and try again."
        MediaPlayerPlaying -> "Playing"
        else -> "LibVLC event $type"
    }

    private interface LibVlc : Library {
        fun libvlc_new(argc: Int, argv: Array<String>?): Pointer?
        fun libvlc_release(instance: Pointer)
        fun libvlc_media_new_path(instance: Pointer, path: String): Pointer?
        fun libvlc_media_new_location(instance: Pointer, location: String): Pointer?
        fun libvlc_media_release(media: Pointer)
        fun libvlc_media_player_new(instance: Pointer): Pointer?
        fun libvlc_media_player_release(player: Pointer)
        fun libvlc_media_player_set_media(player: Pointer, media: Pointer)
        fun libvlc_media_player_play(player: Pointer): Int
        fun libvlc_media_player_set_pause(player: Pointer, doPause: Int)
        fun libvlc_media_player_stop(player: Pointer)
        fun libvlc_media_player_set_position(player: Pointer, position: Float)
        fun libvlc_media_player_set_time(player: Pointer, time: Long)
        fun libvlc_media_player_get_time(player: Pointer): Long
        fun libvlc_media_player_get_length(player: Pointer): Long
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
            NativeLibrary.addSearchPath("libvlc", directory.absolutePath)
            System.setProperty("VLC_PLUGIN_PATH", File(directory, "plugins").absolutePath)
        }
    }

    private companion object {
        val supportedExtensions = setOf("mp4", "mkv", "ts")
        const val MediaPlayerOpening = 258
        const val MediaPlayerBuffering = 259
        const val MediaPlayerPlaying = 260
        const val MediaPlayerPaused = 261
        const val MediaPlayerStopped = 262
        const val MediaEndReached = 265
        const val MediaEncounteredError = 266
        val watchedEvents = intArrayOf(
            MediaPlayerOpening,
            MediaPlayerBuffering,
            MediaPlayerPlaying,
            MediaPlayerPaused,
            MediaPlayerStopped,
            MediaEndReached,
            MediaEncounteredError,
        )
    }
}

/** Extracts the version-pinned runtime bundled in the distribution once per Windows user. */
private object BundledLibVlc {
    private const val archiveResource = "/libvlc/vlc-3.0.23-win64.zip"
    private const val rootDirectory = "vlc-3.0.23/"

    fun install(): File {
        val base = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "Tvivo", "libvlc", "3.0.23")
        val runtime = base.resolve("libvlc.dll")
        if (Files.isRegularFile(runtime)) return base.toFile()
        Files.createDirectories(base.parent)
        val staging = Files.createTempDirectory(base.parent, "3.0.23-")
        try {
            resourceStream().use { input -> ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    if (!entry.name.startsWith(rootDirectory)) return@forEach
                    val target = staging.resolve(entry.name.removePrefix(rootDirectory)).normalize()
                    require(target.startsWith(staging)) { "Invalid bundled runtime entry." }
                    if (entry.isDirectory) Files.createDirectories(target) else {
                        Files.createDirectories(target.parent)
                        Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                            zip.copyTo(output)
                        }
                    }
                }
            } }
            require(Files.isRegularFile(staging.resolve("libvlc.dll"))) { "Bundled LibVLC archive is incomplete." }
            runCatching { Files.move(staging, base, StandardCopyOption.ATOMIC_MOVE) }
                .recoverCatching { Files.move(staging, base) }
        } finally {
            if (Files.exists(staging)) Files.walk(staging).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        return base.toFile()
    }

    private fun resourceStream(): InputStream = checkNotNull(BundledLibVlc::class.java.getResourceAsStream(archiveResource)) {
        "Bundled LibVLC archive is missing."
    }
}
