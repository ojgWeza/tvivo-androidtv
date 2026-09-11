package com.dev.Tvivo.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 5. A bounded, in-memory record of what the app has been doing, readable **on the
 * device** rather than over `adb`.
 *
 * The physical TV this ships to is not going to have a laptop plugged into it, and the
 * failures that matter here — a sync that stopped, a stream the panel refused, an expiry
 * that lapsed — are all invisible from the outside. `Log.i` alone is only useful to
 * whoever is holding the cable.
 *
 * Routine entries stay in memory and are cleared when the process dies. The one exception
 * is a sanitized uncaught crash written synchronously by [CrashDiagnostics], consumed on
 * the next start, and immediately deleted. That file contains exception types and stack
 * frames only — never exception messages or operational/viewing history.
 *
 * **Never log a credential, a URL, or a title.** A stream URL carries the username and
 * password as query parameters, so a single logged URL is the whole account. Call sites
 * pass what happened and to which content *type*, never to which item.
 */
object DiagnosticLog {

    private const val TAG = "Tvivo"

    /** Enough to cover a session's worth of syncs and playback attempts, bounded so a
     *  long-running TV cannot grow it without limit. */
    private const val CAPACITY = 200

    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val atEpochMs: Long,
        val level: Level,
        val area: String,
        val message: String
    ) {
        val time: String
            get() = TIME_FORMAT.format(Date(atEpochMs))
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun info(area: String, message: String) = add(Level.INFO, area, message)
    fun warn(area: String, message: String) = add(Level.WARN, area, message)
    fun error(area: String, message: String) = add(Level.ERROR, area, message)

    private fun add(level: Level, area: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, area, message)
        // Newest first: on a screen with no scrollbar and a D-pad, the thing that just
        // went wrong has to be the thing already on screen.
        _entries.value = (listOf(entry) + _entries.value).take(CAPACITY)

        when (level) {
            Level.INFO -> Log.i(TAG, "[$area] $message")
            Level.WARN -> Log.w(TAG, "[$area] $message")
            Level.ERROR -> Log.e(TAG, "[$area] $message")
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
}
