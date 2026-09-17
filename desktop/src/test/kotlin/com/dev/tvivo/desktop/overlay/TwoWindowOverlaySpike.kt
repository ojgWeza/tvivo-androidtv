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
import com.sun.jna.Pointer
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.math.max

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
    EventQueue.invokeLater { TwoWindowOverlaySpike(fixture).start() }
}

private class TwoWindowOverlaySpike(private val fixture: File) {
    private val host = Frame("Tvivo two-window overlay spike")
    private val canvas = Canvas().apply { background = AwtColor.BLACK; isFocusable = true }
    private val overlay = ComposeWindow()
    private val controlRegions = mutableStateOf(emptyList<Win32OverlayBridge.Region>())
    private val overlayVisible = AtomicBoolean(false)
    private val overlayCreations = AtomicInteger(0)
    private var bridge: Win32OverlayBridge? = null
    private var cinemaMode = false
    private var windowedBounds = Rectangle(80, 80, 960, 540)
    private var player: MpvPlayer? = null
    private var geometryTimer: Timer? = null
    private var autoCloseTimer: Timer? = null
    private val startedAtNanos = System.nanoTime()

    fun start() {
        check(EventQueue.isDispatchThread())
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
                        overlay.isVisible = false
                        overlayVisible.set(false)
                    }
                }
            }
            override fun windowActivated(e: WindowEvent) { if (player != null) showOverlay() }
            override fun windowIconified(e: WindowEvent) { overlay.isVisible = false; overlayVisible.set(false) }
            override fun windowDeiconified(e: WindowEvent) { if (player != null) showOverlay() }
        })
        host.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = scheduleGeometry()
            override fun componentMoved(e: ComponentEvent) = scheduleGeometry()
        })
        host.isVisible = true
        createOverlay()
        player = MpvPlayer(
            onState = { generation, state -> log("state generation=$generation state=$state") },
            onPositionMs = { _, _ -> },
            onPauseChange = { _, paused -> log("paused=$paused") },
        )
        Thread {
            val current = checkNotNull(player)
            current.initialise(canvas)
                .onSuccess { result ->
                    log("player initialized; fixture=${fixture.name}")
                    current.play(fixture, fixture.nameWithoutExtension)
                        .onSuccess { generation -> log("play requested generation=$generation") }
                        .onFailure { log("play failed: ${it.message}") }
                }
                .onFailure { error -> log("player initialize failed: ${error.message}") }
        }.apply { isDaemon = true; name = "two-window-overlay-fixture"; start() }
        showOverlay()
        geometryTimer = Timer(50) { syncGeometry() }.also { it.start() }
        System.getProperty("tvivo.overlay.autoCloseMs")?.toIntOrNull()?.takeIf { it > 0 }?.let { delayMs ->
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
        bridge = Win32OverlayBridge(hostHwnd, overlayHwnd, { controlRegions.value }, { overlay.locationOnScreen })
        overlayCreations.incrementAndGet()
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
        bridge?.let { /* The bridge reads the overlay's current screen origin during hit testing. */ }
    }

    private fun dispose() {
        geometryTimer?.stop()
        geometryTimer = null
        autoCloseTimer?.stop()
        autoCloseTimer = null
        overlay.isVisible = false
        overlayVisible.set(false)
        bridge?.close()
        bridge = null
        overlay.dispose()
        player?.close()
        player = null
        host.dispose()
        log("disposed overlayCreations=${overlayCreations.get()}")
    }

    private fun log(message: String) {
        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
        println("[two-window-overlay +${elapsedMs}ms] $message")
    }
}
