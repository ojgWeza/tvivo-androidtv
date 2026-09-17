package com.dev.tvivo.desktop.render

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

/**
 * Prototype-only libmpv render API binding. This is deliberately test-scoped and is not used by
 * the production wid/Canvas player. Declarations mirror the Windows x64 ABI in section 0.3 of
 * D-DESKTOP-21-OWNED-PLAYER-OVERLAY-PLAN.md.
 */
internal interface MpvRenderLibrary : Library {
    fun mpv_client_api_version(): Long

    fun mpv_create(): Pointer?

    fun mpv_initialize(mpv: Pointer): Int

    fun mpv_set_option_string(mpv: Pointer, name: String, value: String): Int

    fun mpv_terminate_destroy(mpv: Pointer)

    fun mpv_error_string(error: Int): String

    fun mpv_render_context_create(
        renderContext: PointerByReference,
        mpv: Pointer,
        params: Array<MpvRenderParam>,
    ): Int

    fun mpv_render_context_render(renderContext: Pointer, params: Array<MpvRenderParam>): Int

    fun mpv_render_context_update(renderContext: Pointer): Long

    fun mpv_render_context_report_swap(renderContext: Pointer)

    fun mpv_render_context_free(renderContext: Pointer)

    fun mpv_render_context_set_update_callback(
        renderContext: Pointer,
        callback: MpvRenderUpdateCallback?,
        callbackContext: Pointer?,
    )

    fun mpv_get_time_us(mpv: Pointer): Long

    fun mpv_request_log_messages(mpv: Pointer, minLevel: String): Int

    fun mpv_wait_event(mpv: Pointer, timeout: Double): mpv_event.ByReference?
}

@Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
internal open class mpv_event : Structure() {
    @JvmField var eventId: Int = 0
    @JvmField var error: Int = 0
    @JvmField var replyUserdata: Long = 0
    @JvmField var data: Pointer? = null

    class ByReference : mpv_event(), Structure.ByReference
}

@Structure.FieldOrder("prefix", "level", "text", "logLevel")
internal open class mpv_event_log_message : Structure() {
    @JvmField var prefix: String? = null
    @JvmField var level: String? = null
    @JvmField var text: String? = null
    @JvmField var logLevel: Int = 0

    class ByReference : mpv_event_log_message(), Structure.ByReference
}

internal const val MPV_EVENT_LOG_MESSAGE = 2

/** Retain an instance for the complete lifetime of the render context. */
internal fun interface MpvRenderUpdateCallback : Callback {
    fun invoke(context: Pointer)
}

/** Resolve symbols from the current Skiko/WGL context; never from a temporary library handle. */
internal fun interface MpvOpenGlGetProcAddressCallback : Callback {
    fun invoke(context: Pointer, name: String): Pointer
}

@Structure.FieldOrder("type", "data")
internal open class MpvRenderParam : Structure() {
    @JvmField var type: Int = MPV_RENDER_PARAM_INVALID
    @JvmField var data: Pointer? = null
}

@Structure.FieldOrder("getProcAddress", "getProcAddressContext")
internal open class MpvOpenGlInitParams : Structure() {
    @JvmField var getProcAddress: MpvOpenGlGetProcAddressCallback? = null
    @JvmField var getProcAddressContext: Pointer? = null
}

@Structure.FieldOrder("fbo", "w", "h", "internalFormat")
internal open class MpvOpenGlFbo : Structure() {
    @JvmField var fbo: Int = 0
    @JvmField var w: Int = 0
    @JvmField var h: Int = 0
    @JvmField var internalFormat: Int = 0
}

internal const val MPV_RENDER_API_TYPE_OPENGL = "opengl"
internal const val MPV_RENDER_PARAM_INVALID = 0
internal const val MPV_RENDER_PARAM_API_TYPE = 1
internal const val MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2
internal const val MPV_RENDER_PARAM_OPENGL_FBO = 3
internal const val MPV_RENDER_PARAM_FLIP_Y = 4
internal const val MPV_RENDER_PARAM_DEPTH = 5
internal const val MPV_RENDER_PARAM_ADVANCED_CONTROL = 10
internal const val MPV_RENDER_UPDATE_FRAME = 1L
