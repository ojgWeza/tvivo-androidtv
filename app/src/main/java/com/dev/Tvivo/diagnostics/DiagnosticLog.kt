package com.dev.Tvivo.diagnostics

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 5. A bounded, persisted record of what the app has been doing, readable and
 * exportable **on the device** rather than over `adb`.
 *
 * The physical TV this ships to is not going to have a laptop plugged into it, and the
 * failures that matter here — a sync that stopped, a stream the panel refused, an expiry
 * that lapsed — are all invisible from the outside. `Log.i` alone is only useful to
 * whoever is holding the cable.
 *
 * Routine entries are persisted in the app-private files directory. The one exception is
 * a sanitized uncaught crash written synchronously by [CrashDiagnostics], consumed on the
 * next start, and immediately deleted. That file contains exception types and stack frames
 * only — never exception messages or operational/viewing history.
 *
 * **Never log a credential, a URL, or a title.** A stream URL carries the username and
 * password as query parameters, so a single logged URL is the whole account. Call sites
 * pass what happened and to which content *type*, never to which item.
 */
object DiagnosticLog {

    private const val TAG = "Tvivo"
    private const val FILE_NAME = "diagnostics.log"
    private const val EXPORT_FILE_NAME = "tvivo-diagnostics.txt"

    /** Enough to cover a session's worth of syncs and playback attempts, bounded so a
     *  long-running TV cannot grow it without limit. */
    private const val CAPACITY = 200
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    private var storageFile: File? = null

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

    /** Must run before the first entry is written; [TvivoApplication] owns that order. */
    fun initialize(context: Context) {
        synchronized(stateLock) {
            if (storageFile != null) return
            storageFile = File(context.applicationContext.filesDir, FILE_NAME)
            _entries.value = read(storageFile!!)
        }
    }

    fun info(area: String, message: String) = add(Level.INFO, area, message)
    fun warn(area: String, message: String) = add(Level.WARN, area, message)
    fun error(area: String, message: String) = add(Level.ERROR, area, message)

    private fun add(level: Level, area: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, area, message)
        // Newest first: on a screen with no scrollbar and a D-pad, the thing that just
        // went wrong has to be the thing already on screen.
        val snapshot = synchronized(stateLock) {
            (listOf(entry) + _entries.value).take(CAPACITY).also { _entries.value = it }
        }
        persist(snapshot)

        when (level) {
            Level.INFO -> Log.i(TAG, "[$area] $message")
            Level.WARN -> Log.w(TAG, "[$area] $message")
            Level.ERROR -> Log.e(TAG, "[$area] $message")
        }
    }

    fun clear() {
        synchronized(stateLock) { _entries.value = emptyList() }
        persist(emptyList())
    }

    /** Shares a plain-text, sanitized snapshot through the system chooser. */
    fun export(context: Context) {
        val export = File(context.cacheDir, EXPORT_FILE_NAME)
        runCatching {
            export.writeText(render(_entries.value), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.diagnostics",
                export
            )
            val share = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(
                Intent.createChooser(share, "Export Tvivo diagnostics")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { error("diagnostics", "Export failed: ${it.javaClass.simpleName}") }
    }

    private fun persist(snapshot: List<Entry>) {
        val file = storageFile ?: return
        scope.launch {
            writeMutex.withLock {
                runCatching {
                    val temporary = File(file.parentFile, "$FILE_NAME.tmp")
                    temporary.writeText(encode(snapshot), Charsets.UTF_8)
                    if (!temporary.renameTo(file)) {
                        file.writeText(temporary.readText(Charsets.UTF_8), Charsets.UTF_8)
                        temporary.delete()
                    }
                }.onFailure { Log.w(TAG, "Could not persist diagnostics", it) }
            }
        }
    }

    private fun read(file: File): List<Entry> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        file.useLines { lines ->
            lines.mapNotNull { line ->
                val values = line.split('\t', limit = 4)
                if (values.size != 4) return@mapNotNull null
                runCatching {
                    Entry(
                        atEpochMs = values[0].toLong(),
                        level = Level.valueOf(values[1]),
                        area = decode(values[2]),
                        message = decode(values[3])
                    )
                }.getOrNull()
            }.take(CAPACITY).toList()
        }
    }.getOrDefault(emptyList())

    private fun encode(entries: List<Entry>): String = entries.joinToString("\n") { entry ->
        listOf(
            entry.atEpochMs.toString(),
            entry.level.name,
            encode(entry.area),
            encode(entry.message)
        ).joinToString("\t")
    }

    private fun render(entries: List<Entry>): String = buildString {
        appendLine("Tvivo diagnostics — newest first")
        appendLine("Credentials, URLs, and catalogue titles are never recorded.")
        entries.forEach { entry ->
            appendLine("${entry.time} ${entry.level} [${entry.area}] ${entry.message}")
        }
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
}
