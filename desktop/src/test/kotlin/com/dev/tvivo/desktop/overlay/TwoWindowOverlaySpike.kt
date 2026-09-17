package com.dev.tvivo.desktop.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.dev.tvivo.desktop.MpvPlayer
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color as AwtColor
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.system.exitProcess
import kotlin.math.max

private const val PROCESS_MEMORY_COUNTERS_EX_SIZE = 80L
private const val THREADENTRY32_SIZE = 28L

/**
 * Fixture-only entry point. Run through the dedicated Gradle JavaExec task after explicit user
 * approval. It creates a real libmpv wid Canvas and a separate Compose control window; production
 * DesktopShell and MpvPlayer code are intentionally not modified by this spike.
 */
fun main(args: Array<String>) {
    require(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "The two-window overlay spike is Windows-only."
    }
    val fixture = File(System.getProperty("tvivo.overlay.fixture") ?: error("Set -Dtvivo.overlay.fixture=<local .mp4/.mkv/.ts>"))
    require(fixture.isFile && fixture.extension.lowercase() in setOf("mp4", "mkv", "ts")) {
        "Fixture must be an existing local .mp4, .mkv, or .ts file: ${fixture.absolutePath}"
    }
    val cycles = System.getProperty("tvivo.overlay.cycles")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val runId = System.getProperty("tvivo.overlay.runId") ?: UUID.randomUUID().toString()
    EventQueue.invokeLater { runCycles(fixture, runId, cycles, 1) }
}

private fun runCycles(fixture: File, runId: String, cycles: Int, cycleId: Int) {
    TwoWindowOverlaySpike(fixture, runId, cycleId) {
        if (cycleId < cycles) runCycles(fixture, runId, cycles, cycleId + 1) else exitProcess(0)
    }.start()
}

private class TwoWindowOverlaySpike(
    private val fixture: File,
    private val runId: String,
    private val cycleId: Int,
    private val onDisposed: () -> Unit,
) {
    private val host = Frame("Tvivo two-window overlay spike")
    private val canvas = Canvas().apply { background = AwtColor.BLACK; isFocusable = true }
    private val overlay = ComposeWindow()
    private val controlRegions = mutableStateOf(emptyList<Win32OverlayBridge.Region>())
    private val overlayVisible = AtomicBoolean(false)
    private val overlayCreations = AtomicInteger(0)
    private val playerCreations = AtomicInteger(0)
    private val playerGenerations = AtomicInteger(0)
    private var bridge: Win32OverlayBridge? = null
    private var cinemaMode = false
    private var windowedBounds = Rectangle(80, 80, 960, 540)
    private var player: MpvPlayer? = null
    private var geometryTimer: Timer? = null
    private var autoCloseTimer: Timer? = null
    private var metricsTimer: Timer? = null
    private var firstFrameLogged = false
    private var disposed = false
    private val startedAtNanos = System.nanoTime()

    fun start() {
        check(EventQueue.isDispatchThread())
        log("host created fixture=${fixture.name} pid=${ProcessHandle.current().pid()} cycles=${System.getProperty("tvivo.overlay.cycles") ?: 1}")
        log("evidence_manifest runId=$runId cycleId=$cycleId compiled=true compiledRevision=${System.getProperty("tvivo.overlay.revision") ?: "unspecified"}")
        host.layout = BorderLayout()
        host.add(canvas, BorderLayout.CENTER)
        host.minimumSize = Dimension(640, 360)
        host.setBounds(windowedBounds)
        host.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = dispose()
            override fun windowDeactivated(e: WindowEvent) {
                EventQueue.invokeLater {
                    val active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                    if (active !== overlay && active !== host) {
                        hideOverlay("foreground lost")
                        logWindowState()
                    }
                }
            }
            override fun windowActivated(e: WindowEvent) { if (player != null) showOverlay() }
            override fun windowIconified(e: WindowEvent) { hideOverlay("host iconified") }
            override fun windowDeiconified(e: WindowEvent) { if (player != null) showOverlay() }
        })
        host.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = scheduleGeometry()
            override fun componentMoved(e: ComponentEvent) = scheduleGeometry()
        })
        host.isVisible = true
        createOverlay()
        host.extendedState = Frame.NORMAL
        host.toFront()
        host.requestFocus()
        host.requestFocusInWindow()
        bridge?.requestForeground()
        log("host visible=${host.isVisible} displayable=${host.isDisplayable} focused=${host.isFocused} active=${host.isActive} iconified=${host.extendedState == Frame.ICONIFIED}")
        playerCreations.incrementAndGet()
        player = MpvPlayer(
            onState = { generation, state ->
                playerGenerations.set(maxOf(playerGenerations.get(), generation))
                log("state generation=$generation state=$state")
                if (state == "Playing" && !firstFrameLogged) {
                    firstFrameLogged = true
                    log("first frame generation=$generation")
                }
            },
            onPositionMs = { _, _ -> },
            onPauseChange = { generation, paused -> log("state generation=$generation ${if (paused) "paused" else "playing"}") },
        )
        Thread {
            val current = checkNotNull(player)
            current.initialise(canvas)
                .onSuccess { result ->
                    log("player initialized; fixture=${fixture.name} canvasHwnd=${pointerValue(Native.getComponentPointer(canvas))}")
                    log("lifecycle=player initialized")
                    current.play(fixture, fixture.nameWithoutExtension)
                        .onSuccess { generation -> playerGenerations.set(generation); log("lifecycle=load requested generation=$generation") }
                        .onFailure { log("play failed: ${it.message}") }
                }
                .onFailure { error -> log("player initialize failed: ${error.message}") }
        }.apply { isDaemon = true; name = "two-window-overlay-fixture"; start() }
        showOverlay()
        geometryTimer = Timer(50) { syncGeometry() }.also { it.start() }
        metricsTimer = Timer(1000) { logProcessMetrics("periodic") }.also { it.start() }
        val closeDelay = System.getProperty("tvivo.overlay.playMs")?.toIntOrNull()
            ?: System.getProperty("tvivo.overlay.autoCloseMs")?.toIntOrNull()
        closeDelay?.takeIf { it > 0 }?.let { delayMs ->
            autoCloseTimer = Timer(delayMs) { dispose() }.also {
                it.isRepeats = false
                it.start()
            }
        }
    }

    private fun createOverlay() {
        overlay.setUndecorated(true)
        overlay.background = AwtColor(0, 0, 0, 0)
        overlay.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        overlay.isAlwaysOnTop = false
        overlay.setFocusableWindowState(true)
        overlay.setSize(host.width, host.height)
        overlay.setContent {
            MaterialTheme { OverlayContent() }
        }
        overlay.isVisible = true
        val hostHwnd = Native.getComponentPointer(host)
        val overlayHwnd = Native.getComponentPointer(overlay)
        bridge = Win32OverlayBridge(hostHwnd, overlayHwnd, { controlRegions.value }, { overlay.locationOnScreen }, ::log)
        overlayCreations.incrementAndGet()
        log("lifecycle=overlay created hostHwnd=${pointerValue(hostHwnd)} overlayHwnd=${pointerValue(overlayHwnd)} canvasHwnd=${pointerValue(Native.getComponentPointer(canvas))} overlayCreations=${overlayCreations.get()}")
        log("lifecycle=bridge installed")
        syncGeometry()
    }

    @Composable
    private fun OverlayContent() {
        var message by remember { mutableStateOf("Controls are in a separate window") }
        var playing by remember { mutableStateOf(true) }
        var localCinema by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { message = "Overlay HWND ${Pointer.nativeValue(Native.getComponentPointer(overlay))}" }
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xD90A1619)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Fixture overlay", color = Color.White, modifier = Modifier.weight(1f))
                Button(onClick = {
                    playing = !playing
                    if (playing) player?.resume() else player?.pause()
                    message = if (playing) "Resume requested" else "Pause requested"
                }) { Text(if (playing) "Pause" else "Play") }
                Button(onClick = {
                    localCinema = !localCinema
                    setCinemaMode(localCinema)
                    message = if (localCinema) "Cinema bounds" else "Windowed bounds"
                }) { Text(if (localCinema) "Windowed" else "Cinema") }
                Button(onClick = { message = "Control click received" }) { Text("Probe click") }
            }
            Text(
                message,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(18.dp)
                    .background(Color(0xD90A1619)).padding(12.dp),
            )
        }
        // These rectangles are deliberately kept in the fixture host, not inferred by native
        // code. They match the visible top-row controls and bottom status label in physical px.
        SideEffect {
            controlRegions.value = listOf(
                Win32OverlayBridge.Region(0, 0, overlay.width, 92),
                Win32OverlayBridge.Region(0, max(overlay.height - 90, 0), overlay.width, overlay.height),
            )
        }
    }

    private fun setCinemaMode(enabled: Boolean) {
        if (!enabled) {
            host.bounds = windowedBounds
        } else {
            windowedBounds = host.bounds
            val bounds: GraphicsConfiguration = host.graphicsConfiguration
            host.bounds = bounds.bounds
        }
        host.validate()
        scheduleGeometry()
    }

    private fun showOverlay() {
        if (!host.isShowing || host.extendedState == Frame.ICONIFIED) return
        overlay.isVisible = true
        overlayVisible.set(true)
        syncGeometry()
    }

    private fun scheduleGeometry() {
        if (!EventQueue.isDispatchThread()) { EventQueue.invokeLater(::scheduleGeometry); return }
        showOverlay()
        syncGeometry()
    }

    private fun syncGeometry() {
        if (!canvas.isShowing || canvas.width <= 0 || canvas.height <= 0) return
        val screen = canvas.locationOnScreen
        overlay.setBounds(screen.x, screen.y, canvas.width, canvas.height)
        val configuration = canvas.graphicsConfiguration
        val transform = configuration?.defaultTransform
        log("geometry logical=canvas(${canvas.width}x${canvas.height}) overlay(${overlay.width}x${overlay.height}) screen=($screen ${canvas.width}x${canvas.height}) monitor=${configuration?.device?.getIDstring()} dpi=${transform?.scaleX}x${transform?.scaleY} visible=${overlay.isVisible} displayable=${overlay.isDisplayable} focused=${overlay.isFocused} active=${overlay.isActive}")
        bridge?.logWindowState()
    }

    private fun dispose() {
        if (disposed) return
        disposed = true
        geometryTimer?.stop()
        geometryTimer = null
        metricsTimer?.stop()
        metricsTimer = null
        autoCloseTimer?.stop()
        autoCloseTimer = null
        hideOverlay("dispose")
        logProcessMetrics("after overlay hidden")
        check(!overlay.isVisible) { "Overlay must be hidden before player.close()" }
        log("assert overlay hidden before player close=true")
        val bridgeToClose = bridge
        bridge = null
        runCatching { bridgeToClose?.close() }
            .onSuccess { log("lifecycle=bridge closed") }
            .onFailure { log("bridge close failed: ${it.message}") }
        logProcessMetrics("after bridge close wndproc restored")
        overlay.dispose()
        log("lifecycle=overlay disposed")
        logProcessMetrics("after ComposeWindow dispose")
        val closeStarted = System.nanoTime()
        val closeTimeoutMs = System.getProperty("tvivo.overlay.closeTimeoutMs")?.toLongOrNull()?.coerceAtLeast(1) ?: 6000
        runCatching { player?.close() }
            .onSuccess {
                val closeMs = (System.nanoTime() - closeStarted) / 1_000_000
                log("lifecycle=player closed closeMs=$closeMs closeTimeoutMs=$closeTimeoutMs timeout=${closeMs > closeTimeoutMs}")
            }
            .onFailure { log("player close failed: ${it.message}") }
        player = null
        logProcessMetrics("after MpvPlayer.close completed")
        logThreads("after player close")
        host.dispose()
        log("lifecycle=host disposed overlayCreations=${overlayCreations.get()} playerCreations=${playerCreations.get()} playerGenerations=${playerGenerations.get()}")
        logProcessMetrics("after host dispose")
        settleBeforeNextCycle()
    }

    private fun log(message: String) {
        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
        println("[two-window-overlay runId=$runId cycleId=$cycleId timestamp=${java.time.Instant.now()} elapsedMs=$elapsedMs] $message")
    }

    private fun hideOverlay(reason: String) {
        overlay.isVisible = false
        overlayVisible.set(false)
        log("lifecycle=overlay hidden reason=$reason")
    }

    private fun settleBeforeNextCycle() {
        val quietMs = System.getProperty("tvivo.overlay.quietMs")?.toLongOrNull()?.coerceAtLeast(0) ?: 2500
        Thread {
            runCatching { Thread.sleep(quietMs) }
                .onFailure { log("settle sleep interrupted: ${it.message}") }
            System.gc()
            System.runFinalization()
            // Give reference/finalizer processing a short opportunity after the explicit GC.
            runCatching { Thread.sleep(500) }
                .onFailure { log("settle post-GC sleep interrupted: ${it.message}") }
            EventQueue.invokeLater {
                logProcessMetrics("settled after quiet=${quietMs}ms gc=true")
                logThreads("settled")
                onDisposed()
            }
        }.apply {
            // A disposed AWT window can let the JVM's AWT auto-shutdown run before a daemon
            // settler gets to post the next cycle. Keep this handoff alive until onDisposed().
            isDaemon = false
            name = "two-window-overlay-settler"
            start()
        }
    }

    private fun logProcessMetrics(stage: String) {
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val pid = ProcessHandle.current().pid()
        val windows = WindowsProcessMetrics.readCurrentProcess()
        log(
            "process_metrics stage=$stage pid=$pid cpu=${os?.processCpuLoad} " +
                "cpuTimeNs=${os?.processCpuTime} heapUsed=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()} " +
                "handles=${windows?.handles ?: "unavailable"} workingSetBytes=${windows?.workingSetBytes ?: "unavailable"} " +
                "privateBytes=${windows?.privateBytes ?: "unavailable"} nativeThreads=${windows?.nativeThreads ?: "unavailable"} " +
                "jvmThreads=${Thread.activeCount()}",
        )
    }

    private data class WindowsProcessMetrics(
        val handles: Long,
        val workingSetBytes: Long,
        val privateBytes: Long,
        val nativeThreads: Long,
    ) {
        companion object {
            private val kernel32 = Native.load("kernel32", Kernel32::class.java)
            private val psapi = Native.load("psapi", Psapi::class.java)

            fun readCurrentProcess(): WindowsProcessMetrics? = runCatching {
                val process = kernel32.GetCurrentProcess()
                val handleCount = IntByReference()
                check(kernel32.GetProcessHandleCount(process, handleCount))

                val counters = ProcessMemoryCountersEx()
                counters.cb = counters.size()
                counters.write()
                check(psapi.GetProcessMemoryInfo(process, counters, counters.size()))
                counters.read()

                val processId = kernel32.GetCurrentProcessId()
                val snapshot = kernel32.CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0)
                check(Pointer.nativeValue(snapshot) != INVALID_HANDLE_VALUE)
                var nativeThreads = 0L
                try {
                    val entry = ThreadEntry32()
                    entry.dwSize = entry.size()
                    entry.write()
                    if (kernel32.Thread32First(snapshot, entry)) {
                        do {
                            entry.read()
                            if (entry.th32OwnerProcessID == processId) nativeThreads++
                        } while (kernel32.Thread32Next(snapshot, entry))
                    }
                } finally {
                    kernel32.CloseHandle(snapshot)
                }
                WindowsProcessMetrics(
                    handles = handleCount.value.toLong(),
                    workingSetBytes = counters.workingSetSize,
                    privateBytes = counters.privateUsage,
                    nativeThreads = nativeThreads,
                )
            }.onFailure { println("[two-window-overlay] WindowsProcessMetrics.readCurrentProcess failed: $it") }
                .getOrNull()
        }
    }

    private interface Kernel32 : com.sun.jna.Library {
        fun GetCurrentProcess(): Pointer
        fun GetCurrentProcessId(): Int
        fun GetProcessHandleCount(process: Pointer, handleCount: IntByReference): Boolean
        fun CreateToolhelp32Snapshot(flags: Int, processId: Int): Pointer
        fun Thread32First(snapshot: Pointer, entry: ThreadEntry32): Boolean
        fun Thread32Next(snapshot: Pointer, entry: ThreadEntry32): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    private interface Psapi : com.sun.jna.Library {
        fun GetProcessMemoryInfo(process: Pointer, counters: ProcessMemoryCountersEx, size: Int): Boolean
    }

    // JNA reflects Structure fields after the native call. These classes must be
    // publicly reflectable; private nested classes make Field.get(...) fail even
    // when the Win32 function itself succeeds.
    class ProcessMemoryCountersEx : Structure(Memory(PROCESS_MEMORY_COUNTERS_EX_SIZE), ALIGN_NONE) {
        @JvmField var cb = 0
        @JvmField var pageFaultCount = 0
        @JvmField var peakWorkingSetSize = 0L
        @JvmField var workingSetSize = 0L
        @JvmField var quotaPeakPagedPoolUsage = 0L
        @JvmField var quotaPagedPoolUsage = 0L
        @JvmField var quotaPeakNonPagedPoolUsage = 0L
        @JvmField var quotaNonPagedPoolUsage = 0L
        @JvmField var pagefileUsage = 0L
        @JvmField var peakPagefileUsage = 0L
        @JvmField var privateUsage = 0L

        override fun getFieldOrder() = listOf(
            "cb", "pageFaultCount", "peakWorkingSetSize", "workingSetSize",
            "quotaPeakPagedPoolUsage", "quotaPagedPoolUsage", "quotaPeakNonPagedPoolUsage",
            "quotaNonPagedPoolUsage", "pagefileUsage", "peakPagefileUsage", "privateUsage",
        )

    }

    class ThreadEntry32 : Structure(Memory(THREADENTRY32_SIZE), ALIGN_NONE) {
        @JvmField var dwSize = 0
        @JvmField var cntUsage = 0
        @JvmField var th32ThreadID = 0
        @JvmField var th32OwnerProcessID = 0
        @JvmField var tpBasePri = 0
        @JvmField var tpDeltaPri = 0
        @JvmField var dwFlags = 0

        override fun getFieldOrder() = listOf(
            "dwSize", "cntUsage", "th32ThreadID", "th32OwnerProcessID", "tpBasePri", "tpDeltaPri", "dwFlags",
        )

    }

    private companion object {
        const val TH32CS_SNAPTHREAD = 0x00000004
        const val INVALID_HANDLE_VALUE = -1L
    }

    private fun logThreads(stage: String) {
        log("threads stage=$stage " + Thread.getAllStackTraces().keys.joinToString(",") { "${it.name}:${it.isAlive}" })
    }

    private fun pointerValue(pointer: Pointer): String = "0x${Pointer.nativeValue(pointer).toString(16)}"

    private fun logWindowState() = bridge?.logWindowState()
}
