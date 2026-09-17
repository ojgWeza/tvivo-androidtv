package com.dev.tvivo.desktop.render

import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minimal DLL/ABI smoke validation for the prototype binding; this does not create a render context. */
class MpvRenderLibraryValidationTest {
    @Test
    fun bundledDllHasRenderApiAndExpectedWindows64Layouts() {
        val archive = File("src/main/resources/libmpv/libmpv-20260830-win64.zip")
        assertTrue("Bundled libmpv archive not found: ${archive.absolutePath}", archive.isFile)

        val extractionDirectory = Files.createTempDirectory("tvivo-mpv-render-abi-").toFile()
        extractionDirectory.deleteOnExit()
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry("libmpv-2.dll")
                ?: error("Bundled archive does not contain libmpv-2.dll")
            val dll = File(extractionDirectory, entry.name)
            zip.getInputStream(entry).use { input -> dll.outputStream().use(input::copyTo) }
            dll.deleteOnExit()

            val nativeLibrary = NativeLibrary.getInstance(dll.absolutePath)
            val exports = listOf(
                "mpv_render_context_create",
                "mpv_render_context_render",
                "mpv_render_context_update",
                "mpv_render_context_report_swap",
                "mpv_render_context_free",
                "mpv_render_context_set_update_callback",
                "mpv_get_time_us",
            )
            exports.forEach { export -> nativeLibrary.getFunction(export) }

            val library = Native.load(dll.absolutePath, MpvRenderLibrary::class.java)
            val apiVersion = library.mpv_client_api_version()
            val major = (apiVersion ushr 16).toInt()
            val minor = (apiVersion and 0xffffL).toInt()

            assertTrue("Unexpected mpv client API version: $major.$minor", major >= 2)
            assertEquals("mpv_render_param Windows x64 size", 16, MpvRenderParam().size())
            assertEquals("mpv_opengl_init_params Windows x64 size", 16, MpvOpenGlInitParams().size())
            assertEquals("mpv_opengl_fbo Windows x64 size", 16, MpvOpenGlFbo().size())

            println(
                "Bundled libmpv validation: version=$major.$minor, " +
                    "dll=${dll.absolutePath}, dllSha256=${sha256(dll)}, " +
                    "source=${archive.path}, exports=${exports.joinToString(",")}",
            )
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02X".format(byte) }
}
