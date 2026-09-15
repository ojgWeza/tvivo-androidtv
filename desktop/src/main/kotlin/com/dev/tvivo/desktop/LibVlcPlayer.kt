package com.dev.tvivo.desktop

import com.sun.jna.NativeLibrary
import java.awt.Canvas
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

/**
 * Wraps vlcj's [EmbeddedMediaPlayer] instead of hand-rolled JNA bindings. vlcj owns HWND/video
 * surface embedding internally (resolved lazily at play time), which removes the manual
 * isDisplayable/isShowing race we used to handle ourselves in DesktopShell's DisposableEffect.
 */
internal class LibVlcPlayer(
    private val onState: (String) -> Unit,
) : AutoCloseable {
    private var factory: MediaPlayerFactory? = null
    private var mediaPlayer: EmbeddedMediaPlayer? = null
    @Volatile private var pendingResumeMs = 0L
    private val closed = AtomicBoolean(false)
    // Set once a native stop() has been queued (watchdog timeout or the manual Stop button), so
    // close() -- which always needs to happen on Back -- doesn't queue a second blocking native
    // stop() behind the first and double the time it can hang on a stalled socket.
    private val stopRequested = AtomicBoolean(false)
    private val verboseLogging = System.getProperty("tvivo.debug.fixture") != null || System.getProperty("tvivo.debug.verbose") != null

    // stop()/close() are blocking native calls that can themselves hang on a stalled network
    // socket. Every control/lifecycle operation is serialized through this single background
    // thread so (a) a blocked stop() never freezes the caller (Compose UI dispatcher), and
    // (b) close() can never run concurrently with an in-flight stop() -- it queues behind it.
    private val nativeExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "LibVlcPlayer-native").apply { isDaemon = true } }

    private fun submitNative(block: () -> Unit) {
        if (nativeExecutor.isShutdown) return
        runCatching { nativeExecutor.submit { runCatching(block).onFailure { debugLog("native op failed: $it") } } }
    }

    /** Like [submitNative] but waits for the result, so play/playUrl stay serialized with
     * stop/close (never runs concurrently with a release) while still reporting success/failure
     * to the caller. Callers must invoke this from a background thread (never the UI dispatcher). */
    private fun <T> submitNativeBlocking(block: () -> T): T {
        check(!nativeExecutor.isShutdown) { "Player has already been closed." }
        return try {
            nativeExecutor.submit(java.util.concurrent.Callable { block() }).get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    fun initialise(surface: Canvas): Result<Unit> = runCatching {
        check(!closed.get()) { "Player has already been closed." }
        val libVlcDirectory = locateLibVlc()
        NativeLibraryPath.configure(libVlcDirectory)
        val createdFactory = MediaPlayerFactory("--plugin-path=${File(libVlcDirectory, "plugins").absolutePath}")
        factory = createdFactory
        val player = createdFactory.mediaPlayers().newEmbeddedMediaPlayer()
        mediaPlayer = player
        player.videoSurface().set(createdFactory.videoSurfaces().newVideoSurface(surface))
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) { debugLog("event opening"); onState("Opening stream…") }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { debugLog("event buffering $newCache"); onState("Buffering stream…") }
            override fun playing(mediaPlayer: MediaPlayer) {
                debugLog("event playing")
                if (pendingResumeMs > 0L) {
                    // This callback runs on vlcj's own event thread, not nativeExecutor -- route
                    // the seek through it so it can't race a Back-triggered close()/release. Guard
                    // on `closed` inside the queued task: close() may run first if Back/Stop landed
                    // between the event firing and this task executing, and the callback's captured
                    // `mediaPlayer` reference stays non-null even after close() has released it.
                    val resumeMs = pendingResumeMs
                    pendingResumeMs = 0L
                    submitNative { if (!closed.get()) mediaPlayer.controls().setTime(resumeMs) }
                }
                onState("Playing")
            }
            override fun paused(mediaPlayer: MediaPlayer) { debugLog("event paused"); onState("Paused") }
            override fun stopped(mediaPlayer: MediaPlayer) { debugLog("event stopped"); onState("Stopped") }
            override fun finished(mediaPlayer: MediaPlayer) { debugLog("event finished"); onState("Ended") }
            override fun error(mediaPlayer: MediaPlayer) { debugLog("event error"); onState("Playback error. Check the stream is reachable and try again.") }
        })
        onState("Ready")
    }

    private fun debugLog(message: String) {
        if (verboseLogging) System.err.println("[LibVlcPlayer] $message")
    }

    /** Serialized on [nativeExecutor] so play can never run concurrently with a Back-triggered
     * close()/release. Call from a background thread only -- this blocks the caller. */
    fun play(file: File): Result<Unit> = runCatching {
        require(file.isFile) { "Choose an existing local media file." }
        require(file.extension.lowercase() in supportedExtensions) { "Only .mp4, .mkv, and .ts are supported by this POC." }
        debugLog("play(fixture .${file.extension.lowercase()})")
        submitNativeBlocking {
            val player = requirePlayer()
            check(player.media().play(file.absolutePath)) { "LibVLC could not start playback." }
            onState("Playing ${file.extension.lowercase()} fixture")
        }
    }

    /** Plays a user-selected provider URL. Callers must never log this URL: it contains
     * credentials. Serialized on [nativeExecutor]; call from a background thread only. */
    fun playUrl(url: String, resumeFromMs: Long = 0L): Result<Unit> = runCatching {
        require(url.startsWith("http://") || url.startsWith("https://")) { "Unsupported playback URL." }
        pendingResumeMs = resumeFromMs
        debugLog("play(url)")
        submitNativeBlocking {
            val player = requirePlayer()
            check(player.media().play(url, "network-caching=3000")) { "LibVLC could not start playback." }
            onState(if (resumeFromMs > 0L) "Resuming stream…" else "Opening stream…")
        }
    }

    fun pause() = submitNative { mediaPlayer?.let { it.controls().setPause(true); onState("Paused") } }
    fun resume() = submitNative { mediaPlayer?.let { it.controls().setPause(false); onState("Playing") } }
    /** Stop is fire-and-forget on purpose -- see [nativeExecutor]. Callers that need an immediate
     * UI update (e.g. a manual Stop button, or the playback watchdog) should set their own state
     * before calling this, not rely on the async "Stopped" event that may arrive late or never. */
    fun stop() = submitNative { stopRequested.set(true); mediaPlayer?.controls()?.stop() }
    fun seek(position: Float) = submitNative { mediaPlayer?.let { it.controls().setPosition(position.coerceIn(0f, 1f)); onState("Seek ${(position * 100).toInt()}%") } }
    // Native status reads, not routed through nativeExecutor (the polling loop calls these every
    // 5s and must never block behind an in-flight stop()/close()) -- catch failures from a racing
    // release instead of propagating a crash from this read-only sampling path.
    fun positionMs(): Long = runCatching { mediaPlayer?.status()?.time()?.coerceAtLeast(0L) }.getOrNull() ?: 0L
    fun durationMs(): Long = runCatching { mediaPlayer?.status()?.length()?.coerceAtLeast(0L) }.getOrNull() ?: 0L

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        submitNative {
            // Skip a redundant native stop() if one was already queued (watchdog/manual Stop) --
            // it already ran serialized ahead of this task, so calling it again only risks a
            // second blocking hang on the same stalled socket before release can proceed.
            if (!stopRequested.get()) runCatching { mediaPlayer?.controls()?.stop() }
            runCatching { mediaPlayer?.release() }
            runCatching { factory?.release() }
            mediaPlayer = null
            factory = null
            onState("Closed")
        }
        nativeExecutor.shutdown()
    }

    private fun requirePlayer(): EmbeddedMediaPlayer = checkNotNull(mediaPlayer) { "LibVLC is not initialised." }

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

    private object NativeLibraryPath {
        fun configure(directory: File) {
            System.setProperty("jna.library.path", directory.absolutePath)
            NativeLibrary.addSearchPath("libvlc", directory.absolutePath)
            System.setProperty("VLC_PLUGIN_PATH", File(directory, "plugins").absolutePath)
        }
    }

    private companion object {
        val supportedExtensions = setOf("mp4", "mkv", "ts")
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
