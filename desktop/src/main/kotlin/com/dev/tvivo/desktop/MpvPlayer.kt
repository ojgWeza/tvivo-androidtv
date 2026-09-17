package com.dev.tvivo.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Canvas
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * Replaces [LibVlcPlayer]/vlcj. Owns a single background thread that is the *only* thread ever
 * allowed to call into libmpv, for the object's entire lifetime, including mpv_create,
 * mpv_initialize, every command/property call, event draining, and the final
 * mpv_terminate_destroy(). initialise()'s HWND wait (awaitHwnd) is the only step that runs off
 * this thread, because it only reads AWT/JNA state, not libmpv state -- once it has a wid, the
 * actual mpv_create/.../mpv_initialize sequence is submitted onto the owner thread as one atomic
 * blocking task, so nothing else can interleave with it.
 *
 * Every play()/playUrl() bumps [generationCounter] and returns the assigned generation
 * synchronously, before the loadfile command (and its "Opening/Resuming stream…" state) is even
 * submitted to the owner thread -- the caller must record that generation before the first
 * callback for it can possibly fire. handleEvent() re-reads currentGeneration itself when
 * dispatching, so a stale event from an attempt the UI already moved past is still dropped there
 * even if the caller's own bookkeeping lags.
 */
internal class MpvPlayer(
    private val onState: (generation: Int, state: String) -> Unit,
    private val onPositionMs: (generation: Int, positionMs: Long) -> Unit,
    private val onPauseChange: (generation: Int, paused: Boolean) -> Unit = { _, _ -> },
    private val onFullScreenChange: (Boolean) -> Unit = {},
) : AutoCloseable {
    private val verboseLogging = System.getProperty("tvivo.debug.fixture") != null || System.getProperty("tvivo.debug.verbose") != null
    private val closed = AtomicBoolean(false)
    private val generationCounter = AtomicInteger(0)
    @Volatile private var currentGeneration = 0
    @Volatile private var pendingResumeMs = 0L
    // mpv is loaded before the owner thread starts. handle is only assigned/cleared on the owner
    // thread; volatile provides visibility for defensive cross-thread reads, while all libmpv
    // calls remain confined to the owner thread.
    private var mpv: MpvLibrary? = null
    @Volatile private var handle: Pointer? = null

    private val queue = LinkedBlockingQueue<() -> Unit>()
    private val loopThread = Thread({ runLoop() }, "MpvPlayer-owner").apply { isDaemon = true }
    private val TERMINATE_MARKER: () -> Unit = {}
    private val teardownComplete = CompletableFuture<Unit>()

    private fun submit(block: () -> Unit) {
        if (closed.get()) return
        queue.offer(block)
    }

    /** Runs [block] on the owner thread and blocks the caller for its result, bounded so a
     * close() racing this call can't hang the caller forever if the owner thread has already
     * exited its loop (queued after a TERMINATE_MARKER it will never reach). */
    private fun <T> submitBlocking(block: () -> T): T {
        check(!closed.get()) { "Player has already been closed." }
        val future = CompletableFuture<T>()
        queue.offer { runCatching(block).fold(future::complete) { e -> future.completeExceptionally(e) } }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            // The owner thread never got to this task in time. Force a close so that if the task
            // eventually does run, its own closed-check tears the just-created mpv instance down
            // instead of leaving a live player the caller has already given up on.
            closed.set(true)
            forceClose()
            throw IllegalStateException("mpv owner thread did not respond (closed mid-initialisation?).", e)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    fun initialise(surface: Canvas): Result<Unit> = runCatching {
        check(!closed.get()) { "Player has already been closed." }
        val directory = locateLibMpv()
        val lib = MpvLibrary.load(directory)
        mpv = lib
        loopThread.start()
        val wid = awaitHwnd(surface)
        check(!closed.get()) { "Player was closed before initialisation completed." }
        submitBlocking {
            val ctx = checkNotNull(lib.mpv_create()) { "mpv_create failed." }
            handle = ctx
            fun setOption(name: String, value: String) {
                val result = lib.mpv_set_option_string(ctx, name, value)
                check(result >= 0) { "mpv_set_option_string($name=$value) failed: ${lib.mpv_error_string(result)}" }
            }
            try {
                setOption("wid", wid.toString())
                setOption("osc", "yes")
                setOption("input-default-bindings", "yes")
                setOption("input-vo-keyboard", "yes")
                setOption("keep-open", "yes")
                // Keep app-owned Room resume writes as the single resume authority -- disable
                // mpv's own watch-later persistence so the two mechanisms can't disagree.
                setOption("save-position-on-quit", "no")
                val initResult = lib.mpv_initialize(ctx)
                check(initResult >= 0) { "mpv_initialize failed: ${lib.mpv_error_string(initResult)}" }
                // time-pos observation is required (resume tracking and the watchdog's progress
                // check both depend on it) -- fail initialisation visibly rather than silently
                // running without it. pause/duration are used for UI polish only; log-and-continue
                // is an acceptable degradation for those two.
                val obsTimePos = lib.mpv_observe_property(ctx, OBSERVE_TIME_POS, "time-pos", MpvLibrary.MPV_FORMAT_DOUBLE)
                check(obsTimePos >= 0) { "mpv_observe_property(time-pos) failed: ${lib.mpv_error_string(obsTimePos)}" }
                val obsPause = lib.mpv_observe_property(ctx, OBSERVE_PAUSE, "pause", MpvLibrary.MPV_FORMAT_FLAG)
                val obsDuration = lib.mpv_observe_property(ctx, OBSERVE_DURATION, "duration", MpvLibrary.MPV_FORMAT_DOUBLE)
                val obsFullScreen = lib.mpv_observe_property(ctx, OBSERVE_FULLSCREEN, "fullscreen", MpvLibrary.MPV_FORMAT_FLAG)
                if (obsPause < 0 || obsDuration < 0 || obsFullScreen < 0) {
                    debugLog("mpv_observe_property returned an error: pause=$obsPause duration=$obsDuration fullscreen=$obsFullScreen")
                }
            } catch (e: Throwable) {
                runCatching { lib.mpv_terminate_destroy(ctx) }
                handle = null
                throw e
            }
            if (closed.get()) {
                // A Back navigation landed between awaitHwnd() returning and this task actually
                // running on the owner thread -- tear down immediately instead of leaving a live
                // mpv instance the UI has already navigated away from.
                runCatching { lib.mpv_terminate_destroy(ctx) }
                handle = null
            }
        }
        if (!closed.get()) onState(currentGeneration, "Ready")
    }

    /** Waits for the Canvas to become displayable (native window handle assigned) before reading
     * it. Compose's SwingPanel attaching the Canvas is not synchronous with remember/
     * DisposableEffect running -- poll on the EDT until isDisplayable/isShowing, with a bound so
     * a disposed/never-attached surface fails explicitly instead of spinning forever. */
    private fun awaitHwnd(surface: Canvas): Long {
        // initialise() is always invoked from a background dispatcher (never the EDT itself --
        // calling it directly from Compose's DisposableEffect body would deadlock against the
        // invokeAndWait below), but guard both shapes defensively.
        val onEdt = java.awt.EventQueue.isDispatchThread()
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (closed.get()) error("Player was closed while waiting for the video surface.")
            var hwnd: Long? = null
            val check = { if (surface.isDisplayable && surface.isShowing) hwnd = runCatching { nativeHwnd(surface) }.getOrNull() }
            if (onEdt) check() else java.awt.EventQueue.invokeAndWait(check)
            if (hwnd != null && hwnd != 0L) return hwnd!!
            if (onEdt) java.awt.Toolkit.getDefaultToolkit().sync()
            Thread.sleep(20)
        }
        error("Video surface never became displayable; cannot embed mpv.")
    }

    /** JNA's documented replacement for the old java.awt.Component.getPeer()/getHWnd()
     * reflection trick, which no longer works: Component.getPeer() has been removed from the
     * public API on current JDKs (confirmed on this machine's JDK 21 --
     * NoSuchMethodException, not an access/opens problem). Native.getComponentPointer() is
     * JNA's supported way to get an embeddable heavyweight AWT component's native window handle;
     * its raw pointer value is the Win32 HWND mpv's `wid` option expects. */
    private fun nativeHwnd(surface: Canvas): Long? {
        if (!surface.isDisplayable) return null
        val result = runCatching { Native.getComponentPointer(surface) }
        if (result.isFailure) debugLog("nativeHwnd: Native.getComponentPointer failed: ${result.exceptionOrNull()}")
        val pointer = result.getOrNull() ?: return null
        return Pointer.nativeValue(pointer)
    }

    private fun debugLog(message: String) {
        if (verboseLogging) System.err.println("[MpvPlayer] $message")
    }

    /** Returns the generation assigned to this attempt on success, synchronously and before the
     * loadfile command (and its first state update) is submitted to the owner thread -- callers
     * must record it before any callback for this attempt can possibly arrive. */
    fun play(file: File, title: String = file.nameWithoutExtension): Result<Int> = runCatching {
        require(file.isFile) { "Choose an existing local media file." }
        require(file.extension.lowercase() in supportedExtensions) { "Only .mp4, .mkv, and .ts are supported by this POC." }
        val generation = generationCounter.incrementAndGet()
        currentGeneration = generation
        debugLog("play(fixture .${file.extension.lowercase()}) gen=$generation")
        submit { loadfile(generation, file.absolutePath, isResume = false, title = title) }
        generation
    }

    fun playUrl(url: String, resumeFromMs: Long = 0L, title: String): Result<Int> = runCatching {
        require(url.startsWith("http://") || url.startsWith("https://")) { "Unsupported playback URL." }
        pendingResumeMs = resumeFromMs
        val generation = generationCounter.incrementAndGet()
        currentGeneration = generation
        debugLog("play(url) gen=$generation")
        submit { loadfile(generation, url, isResume = resumeFromMs > 0L, title = title) }
        generation
    }

    private fun loadfile(generation: Int, target: String, isResume: Boolean, title: String) {
        val lib = mpv ?: return
        val ctx = handle ?: return
        if (generation != currentGeneration) return
        // mpv's OSC reads media-title. force-media-title must be set by the owner thread before
        // loadfile so the filename never flashes as the title for a new item.
        lib.mpv_set_property_string(ctx, "force-media-title", title.ifBlank { "Tvivo" })
        onState(generation, if (isResume) "Resuming stream…" else "Opening stream…")
        val result = lib.mpv_command(ctx, arrayOf("loadfile", target, "replace", null))
        if (result < 0) {
            debugLog("loadfile failed: ${lib.mpv_error_string(result)}")
            if (generation == currentGeneration) onState(generation, "Playback error: ${lib.mpv_error_string(result)}")
        }
    }

    fun pause() = submit {
        val lib = mpv ?: return@submit
        val ctx = handle ?: return@submit
        lib.mpv_set_property_string(ctx, "pause", "yes")
    }

    fun resume() = submit {
        val lib = mpv ?: return@submit
        val ctx = handle ?: return@submit
        lib.mpv_set_property_string(ctx, "pause", "no")
    }

    fun stop() = submit {
        val lib = mpv ?: return@submit
        val ctx = handle ?: return@submit
        lib.mpv_command(ctx, arrayOf("stop", null))
    }

    fun seek(positionFraction: Float) = submit {
        val lib = mpv ?: return@submit
        val ctx = handle ?: return@submit
        val duration = lastKnownDurationMs / 1000.0
        if (duration <= 0.0) return@submit
        val target = duration * positionFraction.coerceIn(0f, 1f)
        lib.mpv_command(ctx, arrayOf("seek", target.toString(), "absolute", null))
    }

    @Volatile private var lastKnownPositionMs = 0L
    @Volatile private var lastKnownDurationMs = 0L
    fun positionMs(): Long = lastKnownPositionMs
    fun durationMs(): Long = lastKnownDurationMs

    // --- D-Desktop-14 input forwarding -----------------------------------------------------
    // mpv's --wid child window is created WS_DISABLED on Windows whenever it has a parent (see
    // mpv's video/out/w32_common.c and mpv issues #4795/#6762) -- it never receives OS mouse/
    // keyboard input, by design, regardless of AWT/Compose layering. The documented fix (same
    // issues, and #2596/#9910 for OSC specifically) is to forward input explicitly through
    // mpv's own client-API commands so mpv's own OSC/keybindings still own playback control.

    @Volatile private var lastCanvasWidth = 0
    @Volatile private var lastCanvasHeight = 0
    @Volatile private var osdScaleX = 1.0
    @Volatile private var osdScaleY = 1.0

    /** Cheap on the calling (AWT) thread; the actual mpv_get_property_string call happens on the
     * owner thread. Called on Canvas resize and once after every file-loaded event, since
     * osd-width/osd-height can only be read once mpv has a configured video output. */
    fun updateCanvasSize(width: Int, height: Int) {
        lastCanvasWidth = width
        lastCanvasHeight = height
        submit {
            val lib = mpv ?: return@submit
            val ctx = handle ?: return@submit
            refreshOsdScale(lib, ctx)
        }
    }

    private fun refreshOsdScale(lib: MpvLibrary, ctx: Pointer) {
        val osdWidth = readIntProperty(lib, ctx, "osd-width")
        val osdHeight = readIntProperty(lib, ctx, "osd-height")
        if (osdWidth != null && osdWidth > 0 && lastCanvasWidth > 0) osdScaleX = osdWidth.toDouble() / lastCanvasWidth
        if (osdHeight != null && osdHeight > 0 && lastCanvasHeight > 0) osdScaleY = osdHeight.toDouble() / lastCanvasHeight
        debugLog("refreshOsdScale osdWidth=$osdWidth osdHeight=$osdHeight canvas=$lastCanvasWidth x $lastCanvasHeight -> scale=$osdScaleX,$osdScaleY")
    }

    private fun readIntProperty(lib: MpvLibrary, ctx: Pointer, name: String): Int? {
        val ptr = lib.mpv_get_property_string(ctx, name) ?: return null
        return try { ptr.getString(0).toDoubleOrNull()?.toInt() } finally { lib.mpv_free(ptr) }
    }

    private val moveLock = Any()
    private var pendingMoveCoords: DoubleArray? = null
    private var moveTaskQueued = false

    /** Coalesces rapid AWT mouse-move events (up to display refresh rate) into at most one queued
     * owner-thread task at a time, so a fast mouse doesn't back up the single command queue behind
     * playback control commands -- only the most recent position is ever sent (Codex review,
     * 2026-09-15). Coordinates are Canvas-pixel, scaled to mpv's OSD pixel space before sending. */
    fun sendMouseMove(canvasX: Int, canvasY: Int) {
        val shouldQueue: Boolean
        synchronized(moveLock) {
            pendingMoveCoords = doubleArrayOf(canvasX * osdScaleX, canvasY * osdScaleY)
            shouldQueue = !moveTaskQueued
            if (shouldQueue) moveTaskQueued = true
        }
        if (!shouldQueue) return
        submit {
            val coords: DoubleArray?
            synchronized(moveLock) {
                coords = pendingMoveCoords
                pendingMoveCoords = null
                moveTaskQueued = false
            }
            val lib = mpv ?: return@submit
            val ctx = handle ?: return@submit
            coords?.let {
                val result = lib.mpv_command(ctx, arrayOf("mouse", it[0].toInt().toString(), it[1].toInt().toString(), null))
                if (result < 0) debugLog("mouse command failed: ${lib.mpv_error_string(result)}")
                else debugLog("mouse ${it[0].toInt()} ${it[1].toInt()} (scale=$osdScaleX,$osdScaleY canvas=$lastCanvasWidth x $lastCanvasHeight)")
            }
        }
    }

    private val heldKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** mpv's `mouse` command only ever sets position -- clicks are forwarded as ordinary
     * keydown/keyup on the synthetic MBTN_* key names (mpv issues #2596/#9910: a synthetic single
     * click via the mouse command was specifically reported not to drive OSC controls). Flushes
     * the latest pending move first so a press can't act on a stale position mid-drag. */
    fun sendMouseButton(mpvButtonName: String, pressed: Boolean) {
        flushPendingMoveNow()
        sendKey(mpvButtonName, pressed)
    }

    /** Forced OSC visibility (Codex research, 2026-09-15, mpv issue #9910): the synthetic `mouse`
     * command updates mpv's pointer position but does not itself trigger the OSC script's own
     * hover/mouse-activity detector, so OSC never renders from forwarded input alone. `always`/
     * `auto` are osc.lua's own documented script-message modes
     * (https://mpv.io/manual/master/#on-screen-controller) -- tie `always` to Canvas mouse-enter
     * and `auto` to mouse-exit/focus-loss so OSC still auto-hides once the pointer leaves. */
    fun setOscVisibility(mode: String) = submit {
        val lib = mpv ?: return@submit
        val ctx = handle ?: return@submit
        val result = lib.mpv_command(ctx, arrayOf("script-message", "osc-visibility", mode, null))
        if (result < 0) debugLog("osc-visibility $mode failed: ${lib.mpv_error_string(result)}")
        else debugLog("osc-visibility $mode sent ok")
    }

    /** Requests fullscreen through libmpv so the OSC and app controls share one state source. */
    fun setFullScreen(enabled: Boolean) = submit {
        val lib = mpv ?: return@submit
        val ctx = handle ?: return@submit
        val result = lib.mpv_set_property_string(ctx, "fullscreen", if (enabled) "yes" else "no")
        if (result < 0) debugLog("fullscreen=$enabled failed: ${lib.mpv_error_string(result)}")
    }

    fun sendWheel(up: Boolean) = submit {
        val lib = mpv ?: return@submit
        val ctx = handle ?: return@submit
        lib.mpv_command(ctx, arrayOf("keypress", if (up) "WHEEL_UP" else "WHEEL_DOWN", null))
    }

    fun sendKey(mpvKeyName: String, pressed: Boolean) {
        if (pressed) heldKeys.add(mpvKeyName) else heldKeys.remove(mpvKeyName)
        submit {
            val lib = mpv ?: return@submit
            val ctx = handle ?: return@submit
            lib.mpv_command(ctx, arrayOf(if (pressed) "keydown" else "keyup", mpvKeyName, null))
        }
    }

    /** Called on Canvas focus loss/disposal: a key or button that never got its matching keyup
     * (focus stolen mid-press, window closed while dragging) would otherwise stay logically down
     * inside mpv forever (Codex review, 2026-09-15). */
    fun releaseAllHeldKeys() {
        val keys = synchronized(heldKeys) { heldKeys.toList() }
        heldKeys.clear()
        keys.forEach { key -> submit {
            val lib = mpv ?: return@submit
            val ctx = handle ?: return@submit
            lib.mpv_command(ctx, arrayOf("keyup", key, null))
        } }
    }

    private fun flushPendingMoveNow() {
        val coords: DoubleArray?
        synchronized(moveLock) { coords = pendingMoveCoords; pendingMoveCoords = null }
        if (coords == null) return
        submit {
            val lib = mpv ?: return@submit
            val ctx = handle ?: return@submit
            lib.mpv_command(ctx, arrayOf("mouse", coords[0].toInt().toString(), coords[1].toInt().toString(), null))
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) forceClose()
        if (Thread.currentThread() === loopThread) return
        try {
            teardownComplete.get(5, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            debugLog("Timed out waiting for mpv owner thread to terminate.")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            debugLog("Interrupted while waiting for mpv owner thread to terminate.")
        }
    }

    /** Queues the owner thread's shutdown unconditionally -- called both from the public,
     * once-only [close] and from [submitBlocking]'s timeout path, which needs to force a
     * teardown even though [closed] may already be true (set by a concurrent close() whose
     * TERMINATE_MARKER this call is racing to also enqueue; offering it twice is harmless). */
    private fun forceClose() {
        // Terminal task: runs on the owner thread, after every already-queued command, and is
        // itself the last thing that thread does before exiting -- mpv_terminate_destroy() must
        // never run concurrently with a queued play/pause/stop, nor while mpv_wait_event is
        // blocked mid-call on this same thread (it can't be, since this thread issues both).
        if (!loopThread.isAlive) {
            teardownComplete.complete(Unit)
            return
        }
        queue.offer(TERMINATE_MARKER)
    }

    private fun runLoop() {
        try {
            while (true) {
                val task = queue.poll(50, TimeUnit.MILLISECONDS)
                if (task === TERMINATE_MARKER) break
                task?.let { runCatching(it).onFailure { e -> debugLog("queued task failed: $e") } }
                val lib = mpv
                val ctx = handle
                if (lib != null && ctx != null) drainMpvEvents(lib, ctx)
            }
        } finally {
            val lib = mpv
            val ctx = handle
            if (lib != null && ctx != null) runCatching { lib.mpv_terminate_destroy(ctx) }
            mpv = null
            handle = null
            teardownComplete.complete(Unit)
        }
    }

    private fun drainMpvEvents(lib: MpvLibrary, ctx: Pointer) {
        while (true) {
            val event = lib.mpv_wait_event(ctx, 0.0) ?: return
            // Copy every field used out of the event struct immediately -- mpv_wait_event's
            // return value is only valid until the next mpv_wait_event call on this handle.
            val eventId = event.eventId
            val data = event.data
            if (eventId == MpvLibrary.MPV_EVENT_NONE) return
            handleEvent(lib, ctx, eventId, data)
        }
    }

    private fun handleEvent(lib: MpvLibrary, ctx: Pointer, eventId: Int, data: Pointer?) {
        val generation = currentGeneration
        when (eventId) {
            MpvLibrary.MPV_EVENT_FILE_LOADED -> {
                debugLog("event file-loaded gen=$generation")
                val resumeMs = pendingResumeMs
                if (resumeMs > 0L) {
                    pendingResumeMs = 0L
                    lib.mpv_command(ctx, arrayOf("seek", (resumeMs / 1000.0).toString(), "absolute", null))
                }
                // osd-width/osd-height are only meaningful once mpv has a configured video
                // output, not at initialise()-time -- refresh the mouse-move scale factor here.
                refreshOsdScale(lib, ctx)
                onState(generation, "Playing")
            }
            MpvLibrary.MPV_EVENT_END_FILE -> {
                if (data == null) return
                val endFile = MpvEventEndFile.at(data)
                debugLog("event end-file reason=${endFile.reason} error=${endFile.error} gen=$generation")
                val message = when (endFile.reason) {
                    MpvLibrary.MPV_END_FILE_REASON_STOP, MpvLibrary.MPV_END_FILE_REASON_QUIT -> "Stopped"
                    MpvLibrary.MPV_END_FILE_REASON_ERROR -> "Playback error: ${lib.mpv_error_string(endFile.error)}"
                    MpvLibrary.MPV_END_FILE_REASON_EOF -> "Ended"
                    else -> "Stopped"
                }
                onState(generation, message)
            }
            MpvLibrary.MPV_EVENT_SHUTDOWN -> {
                debugLog("event shutdown gen=$generation")
                onState(generation, "Closed")
            }
            MpvLibrary.MPV_EVENT_PROPERTY_CHANGE -> {
                if (data == null) return
                val property = MpvEventProperty.at(data)
                when (property.name) {
                    "time-pos" -> {
                        if (property.format == MpvLibrary.MPV_FORMAT_DOUBLE) {
                            val ptr = property.data ?: return
                            val seconds = ptr.getDouble(0)
                            val ms = (seconds * 1000).toLong().coerceAtLeast(0L)
                            lastKnownPositionMs = ms
                            onPositionMs(generation, ms)
                        }
                    }
                    "duration" -> {
                        if (property.format == MpvLibrary.MPV_FORMAT_DOUBLE) {
                            val ptr = property.data ?: return
                            lastKnownDurationMs = (ptr.getDouble(0) * 1000).toLong().coerceAtLeast(0L)
                        }
                    }
                    "pause" -> {
                        if (property.format == MpvLibrary.MPV_FORMAT_FLAG) {
                            val ptr = property.data ?: return
                            onPauseChange(generation, ptr.getInt(0) != 0)
                        }
                    }
                    "fullscreen" -> {
                        if (property.format == MpvLibrary.MPV_FORMAT_FLAG) {
                            val ptr = property.data ?: return
                            onFullScreenChange(ptr.getInt(0) != 0)
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun locateLibMpv(): File {
        val configured = sequenceOf(
            System.getProperty("tvivo.libmpv.dir"),
            System.getenv("TVIVO_LIBMPV_DIR"),
            BundledLibMpv.install().absolutePath,
        ).filterNotNull().firstOrNull { File(it, "libmpv-2.dll").isFile }
            ?: error("The bundled libmpv runtime could not be prepared. Reinstall Tvivo or set TVIVO_LIBMPV_DIR for development.")
        return File(configured).also {
            require(it.isDirectory && File(it, "libmpv-2.dll").isFile) {
                "libmpv directory must contain libmpv-2.dll: ${it.absolutePath}"
            }
        }
    }

    private companion object {
        const val OBSERVE_TIME_POS = 1L
        const val OBSERVE_PAUSE = 2L
        const val OBSERVE_DURATION = 3L
        const val OBSERVE_FULLSCREEN = 4L
        val supportedExtensions = setOf("mp4", "mkv", "ts")
    }
}

/** Extracts the version-pinned libmpv runtime bundled in the distribution once per Windows user.
 * Mirrors [BundledLibVlc]'s pattern, but verifies the extracted DLL after unzip rather than only
 * checking the archive exists -- a partial/corrupt extraction should fail loudly, not silently
 * hand back a broken directory the way a bare "zip exists" sentinel would. */
private object BundledLibMpv {
    private const val archiveResource = "/libmpv/libmpv-20260830-win64.zip"
    private const val sha256Resource = "/libmpv/libmpv-20260830-win64.zip.sha256"
    private const val version = "20260830"

    fun install(): File {
        val base = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "Tvivo", "libmpv", version)
        val runtime = base.resolve("libmpv-2.dll")
        if (Files.isRegularFile(runtime) && Files.size(runtime) > 0) return base.toFile()
        Files.createDirectories(base.parent)
        verifyArchiveDigest()
        val staging = Files.createTempDirectory(base.parent, "$version-")
        try {
            resourceStream().use { input -> ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val target = staging.resolve(entry.name).normalize()
                    require(target.startsWith(staging)) { "Invalid bundled runtime entry." }
                    if (entry.isDirectory) Files.createDirectories(target) else {
                        Files.createDirectories(target.parent)
                        Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                            zip.copyTo(output)
                        }
                    }
                }
            } }
            val extracted = staging.resolve("libmpv-2.dll")
            require(Files.isRegularFile(extracted) && Files.size(extracted) > 0) { "Bundled libmpv archive is incomplete." }
            runCatching { Files.move(staging, base, StandardCopyOption.ATOMIC_MOVE) }
                .recoverCatching { Files.move(staging, base) }
        } finally {
            if (Files.exists(staging)) Files.walk(staging).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        require(Files.isRegularFile(base.resolve("libmpv-2.dll")) && Files.size(base.resolve("libmpv-2.dll")) > 0) {
            "libmpv extraction did not produce a usable libmpv-2.dll."
        }
        return base.toFile()
    }

    private fun resourceStream(): InputStream = checkNotNull(BundledLibMpv::class.java.getResourceAsStream(archiveResource)) {
        "Bundled libmpv archive is missing."
    }

    /** Verifies the packaged archive's SHA-256 against its checked-in sidecar before extraction --
     * the post-extraction "is libmpv-2.dll nonzero size" check only catches truncation, not the
     * archive having been substituted or corrupted in a way that still unzips cleanly. */
    private fun verifyArchiveDigest() {
        val expected = checkNotNull(BundledLibMpv::class.java.getResourceAsStream(sha256Resource)) {
            "Bundled libmpv archive checksum sidecar is missing."
        }.use { it.readBytes().toString(Charsets.US_ASCII).trim().lowercase() }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        resourceStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Bundled libmpv archive checksum mismatch (expected $expected, got $actual)." }
    }
}
