package com.dev.tvivo.desktop.overlay

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicBoolean

/** Test-only Win32 hit-test/ownership boundary for the two-window fixture spike. */
internal class Win32OverlayBridge(
    private val hostHwnd: Pointer,
    private val overlayHwnd: Pointer,
    private val controlRegions: () -> List<Region>,
    private val overlayLocationOnScreen: () -> java.awt.Point,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val user32 = Native.load("user32", Api::class.java)
    private val originalWndProc: Pointer
    private val wndProc = HitTestWndProc { _, message, _, lParam ->
        if (message != WM_NCHITTEST) return@HitTestWndProc null
        val packed = Pointer.nativeValue(lParam ?: return@HitTestWndProc null)
        val screenX = signedWord(packed and 0xffffL)
        val screenY = signedWord((packed ushr 16) and 0xffffL)
        val origin = overlayLocationOnScreen()
        val localX = screenX - origin.x
        val localY = screenY - origin.y
        val selectedRegion = controlRegions().indexOfFirst { it.contains(localX, localY) }
        val result = if (selectedRegion >= 0) HTCLIENT else HTTRANSPARENT
        log("WM_NCHITTEST screen=($screenX,$screenY) local=($localX,$localY) region=$selectedRegion return=$result")
        Pointer.createConstant(result.toLong())
    }
    private val closed = AtomicBoolean(false)
    private var restoreCount = 0

    init {
        val existingStyle = Pointer.nativeValue(user32.GetWindowLongPtrW(overlayHwnd, GWL_EXSTYLE))
        val styleResult = user32.SetWindowLongPtrW(
            overlayHwnd,
            GWL_EXSTYLE,
            Pointer.createConstant(existingStyle or WS_EX_LAYERED.toLong() or WS_EX_TOOLWINDOW.toLong()),
        )
        log("SetWindowLongPtrW exstyle hwnd=${pointerValue(overlayHwnd)} result=${pointerValue(styleResult)}")
        // GWLP_HWNDPARENT sets the owner for a top-level window; it does not reparent the overlay
        // into the heavyweight video Canvas.
        val ownerResult = user32.SetWindowLongPtrW(overlayHwnd, GWLP_HWNDPARENT, hostHwnd)
        log("SetWindowLongPtrW owner hwnd=${pointerValue(overlayHwnd)} owner=${pointerValue(hostHwnd)} result=${pointerValue(ownerResult)}")
        originalWndProc = user32.SetWindowLongPtrW(
            overlayHwnd,
            GWL_WNDPROC,
            CallbackReference.getFunctionPointer(wndProc),
        ) ?: error("SetWindowLongPtrW(GWLP_WNDPROC) returned null")
        log("SetWindowLongPtrW wndproc hwnd=${pointerValue(overlayHwnd)} result=${pointerValue(originalWndProc)}")
        val layeredResult = user32.SetLayeredWindowAttributes(overlayHwnd, 0, 255.toByte(), LWA_ALPHA)
        log("SetLayeredWindowAttributes hwnd=${pointerValue(overlayHwnd)} result=$layeredResult")
        check(layeredResult) {
            "SetLayeredWindowAttributes failed for overlay HWND"
        }
        val positionResult = user32.SetWindowPos(
            overlayHwnd,
            hostHwnd,
            0,
            0,
            0,
            0,
            SWP_NOSIZE or SWP_NOMOVE or SWP_NOACTIVATE or SWP_FRAMECHANGED,
        )
        log("SetWindowPos overlay=${pointerValue(overlayHwnd)} owner=${pointerValue(hostHwnd)} result=$positionResult")
        check(positionResult) { "SetWindowPos failed for overlay HWND" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            log("bridge close ignored: already closed")
            return
        }
        val result = runCatching { user32.SetWindowLongPtrW(overlayHwnd, GWL_WNDPROC, originalWndProc) }
        restoreCount++
        log("SetWindowLongPtrW restore hwnd=${pointerValue(overlayHwnd)} result=${result.getOrNull()?.let(::pointerValue)} success=${result.isSuccess} restoreCount=$restoreCount")
        check(restoreCount == 1) { "Original window procedure restored more than once" }
        result.getOrThrow()
    }

    fun logWindowState() {
        log("windows host=${pointerValue(hostHwnd)} overlay=${pointerValue(overlayHwnd)} foreground=${pointerValue(user32.GetForegroundWindow())} focus=${pointerValue(user32.GetFocus())}")
    }

    fun requestForeground() {
        user32.ShowWindow(hostHwnd, SW_RESTORE)
        val broughtToTop = user32.BringWindowToTop(hostHwnd)
        val foreground = user32.SetForegroundWindow(hostHwnd)
        val actual = user32.GetForegroundWindow()
        log("foreground request host=${pointerValue(hostHwnd)} bringToTop=$broughtToTop setForeground=$foreground actual=${pointerValue(actual)} hostForeground=${actual == hostHwnd}")
    }

    private fun pointerValue(pointer: Pointer?): String =
        pointer?.let { "0x${Pointer.nativeValue(it).toString(16)}" } ?: "null"

    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    }

    private fun signedWord(value: Long): Int = value.toInt().toShort().toInt()

    private interface Api : Library {
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Pointer
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Pointer): Pointer?
        fun SetLayeredWindowAttributes(hwnd: Pointer, colorKey: Int, alpha: Byte, flags: Int): Boolean
        fun SetWindowPos(hwnd: Pointer, insertAfter: Pointer, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetForegroundWindow(): Pointer?
        fun GetFocus(): Pointer?
        fun ShowWindow(hwnd: Pointer, command: Int): Boolean
        fun BringWindowToTop(hwnd: Pointer): Boolean
        fun SetForegroundWindow(hwnd: Pointer): Boolean
    }

    private fun interface HitTestWndProc : Callback {
        fun invoke(hwnd: Pointer?, message: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
    }

    private companion object {
        const val GWL_EXSTYLE = -20
        const val GWL_WNDPROC = -4
        const val GWLP_HWNDPARENT = -8
        const val WS_EX_LAYERED = 0x00080000
        const val WS_EX_TOOLWINDOW = 0x00000080
        const val LWA_ALPHA = 0x00000002
        const val WM_NCHITTEST = 0x0084
        const val HTCLIENT = 1
        const val HTTRANSPARENT = -1
        const val SWP_NOSIZE = 0x0001
        const val SWP_NOMOVE = 0x0002
        const val SWP_NOACTIVATE = 0x0010
        const val SWP_FRAMECHANGED = 0x0020
        const val SW_RESTORE = 9
    }
}
