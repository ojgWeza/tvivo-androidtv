package com.dev.tvivo.desktop.render

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.awt.EventQueue
import java.awt.Frame
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
import org.junit.Test

/**
 * Prototype-only current-GL lifecycle check. It deliberately loads no media and never touches
 * the production player or desktop shell.
 */
class MpvRenderContextSkikoSmokeTest {
    @Test
    fun createsAndFreesRenderContextInsideCurrentSkikoOpenGlFrame() {
        assumeWindows()
        System.setProperty("skiko.renderApi", "OPENGL")

        val archive = java.io.File("src/main/resources/libmpv/libmpv-20260830-win64.zip")
        assertTrue("Bundled libmpv archive not found: ${archive.absolutePath}", archive.isFile)
        val directory = extractDll(archive)
        val lib = Native.load(directory.resolve("libmpv-2.dll").absolutePath, MpvRenderLibrary::class.java)
        val core = checkNotNull(lib.mpv_create()) { "mpv_create returned null" }
        check(lib.mpv_set_option_string(core, "vo", "libmpv") >= 0) { "mpv_set_option_string(vo=libmpv) failed" }
        check(lib.mpv_set_option_string(core, "gpu-api", "opengl") >= 0) { "mpv_set_option_string(gpu-api=opengl) failed" }
        check(lib.mpv_set_option_string(core, "msg-level", "all=trace") >= 0) { "mpv_set_option_string(msg-level) failed" }
        check(lib.mpv_initialize(core) >= 0) { "mpv_initialize failed" }

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
                        val renderContext = PointerByReference()
                        val apiType = Memory((MPV_RENDER_API_TYPE_OPENGL.length + 1).toLong()).apply {
                            setString(0, MPV_RENDER_API_TYPE_OPENGL)
                        }
                        val init = MpvOpenGlInitParams().apply {
                            getProcAddress = WglProcAddressCallback()
                            getProcAddressContext = null
                            write()
                        }
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
                        assertEquals("mpv_render_context_create failed: ${lib.mpv_error_string(createResult)}", 0, createResult)
                        assertNotNull(renderContext.value)
                        // This is intentionally the first libmpv render-context teardown operation.
                        lib.mpv_render_context_free(renderContext.value)
                        } catch (t: Throwable) {
                            failure.set(t)
                        } finally {
                            finished.countDown()
                        }
                    }
                }
                window = JFrame("Tvivo Skiko render smoke")
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
            directory.deleteRecursively()
        }
    }

    private fun assumeWindows() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").contains("Windows"))
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless())
    }

    private fun extractDll(archive: java.io.File): java.io.File {
        val directory = java.nio.file.Files.createTempDirectory("tvivo-mpv-skiko-").toFile()
        java.util.zip.ZipFile(archive).use { zip ->
            val entry = checkNotNull(zip.getEntry("libmpv-2.dll"))
            directory.resolve(entry.name).outputStream().use { output ->
                zip.getInputStream(entry).use { input -> input.copyTo(output) }
            }
        }
        return directory
    }

    private class WglProcAddressCallback : MpvOpenGlGetProcAddressCallback {
        private val opengl = NativeLibrary.getInstance("opengl32")
        private val wglGetProcAddress: Function = opengl.getFunction("wglGetProcAddress")

        override fun invoke(context: Pointer, name: String): Pointer {
            val pointer = wglGetProcAddress.invokePointer(arrayOf(name))
            if (pointer != null && Pointer.nativeValue(pointer) !in setOf(0L, 1L, 2L, 3L, -1L)) {
                return pointer
            }
            // wglGetProcAddress does not return addresses for the original GL 1.1
            // entry points. mpv's render_gl.h explicitly requires this fallback.
            return runCatching { opengl.getFunction(name) as Pointer }.getOrDefault(Pointer.NULL)
        }
    }
}
