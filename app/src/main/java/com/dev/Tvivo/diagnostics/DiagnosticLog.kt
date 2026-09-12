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
import java.util.regex.Pattern

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
        /** Stable export schema: timestamp, screen, event, payload, severity. */
        val timestamp: Long,
        val screen: String,
        val event: String,
        val payload: String,
        val severity: Level
    ) {
        val time: String
            get() = TIME_FORMAT.format(Date(timestamp))

        // Compatibility names keep older, deliberately non-sensitive call sites concise.
        val atEpochMs get() = timestamp
        val area get() = screen
        val message get() = "$event: $payload"
        val level get() = severity
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

    fun info(screen: String, event: String, payload: String = "") =
        add(Level.INFO, screen, event, payload)
    fun warn(screen: String, event: String, payload: String = "") =
        add(Level.WARN, screen, event, payload)
    fun error(screen: String, event: String, payload: String = "") =
        add(Level.ERROR, screen, event, payload)

    private fun add(level: Level, screen: String, event: String, payload: String) {
        val entry = Entry(
            timestamp = System.currentTimeMillis(),
            screen = redact(screen),
            event = redact(event),
            payload = redact(payload),
            severity = level
        )
        // Newest first: on a screen with no scrollbar and a D-pad, the thing that just
        // went wrong has to be the thing already on screen.
        val snapshot = synchronized(stateLock) {
            (listOf(entry) + _entries.value).take(CAPACITY).also { _entries.value = it }
        }
        persist(snapshot)

        when (level) {
            Level.INFO -> Log.i(TAG, "[${entry.screen}] ${entry.message}")
            Level.WARN -> Log.w(TAG, "[${entry.screen}] ${entry.message}")
            Level.ERROR -> Log.e(TAG, "[${entry.screen}] ${entry.message}")
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

    internal fun read(file: File): List<Entry> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        file.useLines { lines ->
            lines.mapNotNull { line ->
                val values = line.split('\t', limit = 4)
                if (values.size != 4) return@mapNotNull null
                runCatching {
                    val message = decode(values[3])
                    val separator = message.indexOf('\u0000')
                    Entry(
                        timestamp = values[0].toLong(),
                        severity = Level.valueOf(values[1]),
                        screen = decode(values[2]),
                        event = if (separator >= 0) message.substring(0, separator) else "message",
                        payload = if (separator >= 0) message.substring(separator + 1) else message
                    )
                }.getOrNull()
            }.take(CAPACITY).toList()
        }
    }.getOrDefault(emptyList())

    internal fun encode(entries: List<Entry>): String = entries.joinToString("\n") { entry ->
        listOf(
            entry.timestamp.toString(),
            entry.severity.name,
            encode(entry.screen),
            encode("${entry.event}\u0000${entry.payload}")
        ).joinToString("\t")
    }

    private fun render(entries: List<Entry>): String = buildString {
        appendLine("Tvivo diagnostics — newest first")
        appendLine("Credentials, URLs, and catalogue titles are never recorded.")
        entries.forEach { entry ->
            appendLine("${entry.time}\t${entry.severity}\t${entry.screen}\t${entry.event}\t${entry.payload}")
        }
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)

    /** Last line of defence: diagnostics must remain safe when a caller gets careless. */
    internal fun redact(value: String): String {
        var result = value
        result = URL_CREDENTIALS.matcher(result).replaceAll("\$1<redacted>@")
        result = URL_HOST.matcher(result).replaceAll("\$1<redacted-host>")
        result = SECRET_FIELD.matcher(result).replaceAll("\$1=<redacted>")
        result = AUTHORIZATION.matcher(result).replaceAll("\$1 <redacted>")
        return result
    }

    private val URL_CREDENTIALS = Pattern.compile("(https?://)[^/@\\s:]+:[^/@\\s]+@", Pattern.CASE_INSENSITIVE)
    private val URL_HOST = Pattern.compile("(https?://)(?:[^/@\\s]+@)?[^/\\s?#]+", Pattern.CASE_INSENSITIVE)
    private val SECRET_FIELD = Pattern.compile("\\b(password|pass|token|authorization|username|user|host|server)\\s*[=:]\\s*[^\\s,;]+", Pattern.CASE_INSENSITIVE)
    private val AUTHORIZATION = Pattern.compile("\\b(Bearer|Basic)\\s+[^\\s]+", Pattern.CASE_INSENSITIVE)

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
}
