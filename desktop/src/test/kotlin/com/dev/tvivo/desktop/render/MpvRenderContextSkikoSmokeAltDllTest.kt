package com.dev.tvivo.desktop.render

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkikoRenderDelegate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Same current-GL lifecycle smoke check as MpvRenderContextSkikoSmokeTest, but pointed at an
 * alternate scratch libmpv-2.dll via -Dtvivo.mpv.altDllPath=<path> to compare a candidate GL-capable
 * build against the bundled one. Skipped entirely if that property is not set. Never touches the
 * bundled/production archive.
 */
class MpvRenderContextSkikoSmokeAltDllTest {
    @Test
    fun createsAndFreesRenderContextAgainstAlternateDll() {
        assumeWindows()
        val dllPath = System.getProperty("tvivo.mpv.altDllPath")
        Assume.assumeTrue("tvivo.mpv.altDllPath system property not set; skipping", dllPath != null)
        val dll = java.io.File(dllPath!!)
        assertTrue("Alternate libmpv DLL not found: ${dll.absolutePath}", dll.isFile)

        System.setProperty("skiko.renderApi", "OPENGL")

        val lib = Native.load(dll.absolutePath, MpvRenderLibrary::class.java)
        val core = checkNotNull(lib.mpv_create()) { "mpv_create returned null" }
        check(lib.mpv_set_option_string(core, "vo", "libmpv") >= 0) { "mpv_set_option_string(vo=libmpv) failed" }
        check(lib.mpv_set_option_string(core, "gpu-api", "opengl") >= 0) { "mpv_set_option_string(gpu-api=opengl) failed" }
        check(lib.mpv_set_option_string(core, "msg-level", "all=trace") >= 0) { "mpv_set_option_string(msg-level) failed" }
        check(lib.mpv_initialize(core) >= 0) { "mpv_initialize failed" }
        lib.mpv_request_log_messages(core, "trace")

        fun drainLogs(label: String) {
            while (true) {
                val event = lib.mpv_wait_event(core, 0.0) ?: break
                if (event.eventId == 0) break
                if (event.eventId == MPV_EVENT_LOG_MESSAGE && event.data != null) {
                    val msg = com.sun.jna.Structure.newInstance(
                        mpv_event_log_message::class.java,
                        event.data,
                    ).apply { read() }
                    println("[mpv:$label] ${msg.prefix} ${msg.level}: ${msg.text?.trimEnd()}")
                }
            }
        }
        drainLogs("post-init")

        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var frameSeen = false
        var window: JFrame? = null
        var layer: SkiaLayer? = null

        try {
            EventQueue.invokeAndWait {
                layer = SkiaLayer(null, false, false, GraphicsApi.OPENGL, SkiaLayerAnalytics.Empty)
                layer!!.renderDelegate = object : SkikoRenderDelegate {
                    override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
                        if (frameSeen) return
                        frameSeen = true
                        try {
                            val opengl = NativeLibrary.getInstance("opengl32")
                            val glGetString = opengl.getFunction("glGetString")
                            val wglGetCurrentContext = opengl.getFunction("wglGetCurrentContext")
                            val currentContext = wglGetCurrentContext.invokePointer(arrayOf())
                            val versionPtr = glGetString.invokePointer(arrayOf(0x1F02)) // GL_VERSION
                            val version = versionPtr?.getString(0) ?: "<null>"
                            println("[gl-diag] wglGetCurrentContext=$currentContext GL_VERSION=$version")
                            val renderContext = PointerByReference()
                            val apiType = Memory((MPV_RENDER_API_TYPE_OPENGL.length + 1).toLong()).apply {
                                setString(0, MPV_RENDER_API_TYPE_OPENGL)
                            }
                            val init = MpvOpenGlInitParams().apply {
                                getProcAddress = WglProcAddressCallback()
                                getProcAddressContext = null
                                write()
                            }
                            println(
                                "[init-diag] init.pointer=${init.pointer} " +
                                    "rawFnPtrBytes=${init.pointer.getPointer(0)} " +
                                    "callbackObj=${init.getProcAddress}",
                            )
                            val advanced = Memory(4).apply { setInt(0, 1) }
                            @Suppress("UNCHECKED_CAST")
                            val params = MpvRenderParam().toArray(4) as Array<MpvRenderParam>
                            params[0].type = MPV_RENDER_PARAM_API_TYPE
                            params[0].data = apiType
                            params[1].type = MPV_RENDER_PARAM_OPENGL_INIT_PARAMS
                            params[1].data = init.pointer
                            params[2].type = MPV_RENDER_PARAM_ADVANCED_CONTROL
                            params[2].data = advanced
                            params.forEach { it.write() }
                            val createResult = lib.mpv_render_context_create(renderContext, core, params)
                            drainLogs("post-create")
                            assertEquals(
                                "mpv_render_context_create failed: ${lib.mpv_error_string(createResult)}",
                                0,
                                createResult,
                            )
                            assertNotNull(renderContext.value)
                            lib.mpv_render_context_free(renderContext.value)
                        } catch (t: Throwable) {
                            failure.set(t)
                        } finally {
                            finished.countDown()
                        }
                    }
                }
                window = JFrame("Tvivo Skiko render smoke (alt DLL)")
                window!!.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                window!!.add(layer)
                window!!.setSize(320, 180)
                window!!.setLocationRelativeTo(null)
                window!!.isVisible = true
                layer!!.needRedraw()
            }

            assertTrue("Skiko OpenGL frame did not arrive", finished.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertEquals("Expected the configured Skiko layer to use OpenGL", GraphicsApi.OPENGL, layer!!.renderApi)
        } finally {
            EventQueue.invokeAndWait {
                window?.dispose()
                layer?.dispose()
            }
            lib.mpv_terminate_destroy(core)
        }
    }

    private fun assumeWindows() {
        Assume.assumeTrue(System.getProperty("os.name").contains("Windows"))
        Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
    }

    private class WglProcAddressCallback : MpvOpenGlGetProcAddressCallback {
        private val opengl = NativeLibrary.getInstance("opengl32")
        private val wglGetProcAddress: Function = opengl.getFunction("wglGetProcAddress")

        var callCount = 0

        override fun invoke(context: Pointer, name: String): Pointer {
            callCount++
            val pointer = wglGetProcAddress.invokePointer(arrayOf(name))
            val result = if (pointer != null && Pointer.nativeValue(pointer) !in setOf(0L, 1L, 2L, 3L, -1L)) {
                pointer
            } else {
                // wglGetProcAddress does not return addresses for the original GL 1.1
                // entry points. mpv's render_gl.h explicitly requires this fallback.
                runCatching { opengl.getFunction(name) as Pointer }.getOrDefault(Pointer.NULL)
            }
            println("[proc-addr] #$callCount name=$name wgl=$pointer -> $result")
            return result
        }
    }
}
