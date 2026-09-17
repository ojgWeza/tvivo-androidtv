package com.dev.tvivo.desktop.overlay

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** Test-only Win32 hit-test/ownership boundary for the two-window fixture spike. */
internal class Win32OverlayBridge(
    private val hostHwnd: Pointer,
    private val overlayHwnd: Pointer,
    private val controlRegions: () -> List<Region>,
    private val overlayLocationOnScreen: () -> java.awt.Point,
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
        if (controlRegions().any { it.contains(localX, localY) }) {
            Pointer.createConstant(HTCLIENT.toLong())
        } else {
            Pointer.createConstant(HTTRANSPARENT.toLong())
        }
    }

    init {
        val existingStyle = Pointer.nativeValue(user32.GetWindowLongPtrW(overlayHwnd, GWL_EXSTYLE))
        user32.SetWindowLongPtrW(
            overlayHwnd,
            GWL_EXSTYLE,
            Pointer.createConstant(existingStyle or WS_EX_LAYERED.toLong() or WS_EX_TOOLWINDOW.toLong()),
        )
        // GWLP_HWNDPARENT sets the owner for a top-level window; it does not reparent the overlay
        // into the heavyweight video Canvas.
        user32.SetWindowLongPtrW(overlayHwnd, GWLP_HWNDPARENT, hostHwnd)
        originalWndProc = user32.SetWindowLongPtrW(
            overlayHwnd,
            GWL_WNDPROC,
            CallbackReference.getFunctionPointer(wndProc),
        ) ?: error("SetWindowLongPtrW(GWLP_WNDPROC) returned null")
        check(user32.SetLayeredWindowAttributes(overlayHwnd, 0, 255.toByte(), LWA_ALPHA)) {
            "SetLayeredWindowAttributes failed for overlay HWND"
        }
        user32.SetWindowPos(
            overlayHwnd,
            hostHwnd,
            0,
            0,
            0,
            0,
            SWP_NOSIZE or SWP_NOMOVE or SWP_NOACTIVATE or SWP_FRAMECHANGED,
        )
    }

    override fun close() {
        runCatching { user32.SetWindowLongPtrW(overlayHwnd, GWL_WNDPROC, originalWndProc) }
    }

    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    }

    private fun signedWord(value: Long): Int = value.toInt().toShort().toInt()

    private interface Api : Library {
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Pointer
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Pointer): Pointer?
        fun SetLayeredWindowAttributes(hwnd: Pointer, colorKey: Int, alpha: Byte, flags: Int): Boolean
        fun SetWindowPos(hwnd: Pointer, insertAfter: Pointer, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
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
    }
}
