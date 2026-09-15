package com.dev.tvivo.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Hand-rolled JNA binding against mpv/client.h's C ABI. No mature Kotlin/JVM mpv wrapper exists
 * (unlike vlcj for LibVLC), so this interface declares only the subset of the client API this
 * app actually uses: handle lifecycle, option/property set, command dispatch, and the
 * event/property-observation loop.
 */
internal interface MpvLibrary : Library {
    fun mpv_client_api_version(): Long
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_command_async(ctx: Pointer, replyUserdata: Long, args: Array<String?>): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeoutSeconds: Double): MpvEvent.ByReference?
    fun mpv_error_string(error: Int): String

    companion object {
        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_DOUBLE = 5

        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_PROPERTY_CHANGE = 22

        const val MPV_END_FILE_REASON_EOF = 0
        const val MPV_END_FILE_REASON_STOP = 2
        const val MPV_END_FILE_REASON_QUIT = 3
        const val MPV_END_FILE_REASON_ERROR = 4

        fun load(directory: java.io.File): MpvLibrary {
            System.setProperty("jna.library.path", directory.absolutePath)
            return Native.load(java.io.File(directory, "libmpv-2.dll").absolutePath, MpvLibrary::class.java)
        }
    }
}

/** Mirrors `struct mpv_event`. Field order and types must match client.h exactly for JNA's
 * memory layout to line up. */
@Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
internal open class MpvEvent : Structure() {
    @JvmField var eventId: Int = 0
    @JvmField var error: Int = 0
    @JvmField var replyUserdata: Long = 0
    @JvmField var data: Pointer? = null

    class ByReference : MpvEvent(), Structure.ByReference
}

/** Mirrors `struct mpv_event_property`. Only used for MPV_FORMAT_DOUBLE (time-pos) and
 * MPV_FORMAT_FLAG (pause) observations -- this app never observes a string-typed property. */
@Structure.FieldOrder("name", "format", "data")
internal open class MpvEventProperty : Structure() {
    @JvmField var name: String? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null

    class ByReference : MpvEventProperty(), Structure.ByReference

    companion object {
        fun at(pointer: Pointer): MpvEventProperty {
            val struct = MpvEventProperty()
            struct.useMemory(pointer)
            struct.read()
            return struct
        }
    }
}

/** Mirrors `struct mpv_event_end_file`'s leading fields (reason, error) -- the trailing
 * playlist_entry_id field this app never reads is omitted; JNA only needs the fields declared. */
@Structure.FieldOrder("reason", "error")
internal open class MpvEventEndFile : Structure() {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0

    companion object {
        fun at(pointer: Pointer): MpvEventEndFile {
            val struct = MpvEventEndFile()
            struct.useMemory(pointer)
            struct.read()
            return struct
        }
    }
}
