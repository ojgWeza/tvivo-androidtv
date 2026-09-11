package com.dev.Tvivo.diagnostics

import android.content.Context
import android.os.Process
import java.io.File
import java.io.FileOutputStream

/** Persists one sanitized uncaught crash so it survives long enough to be read on TV. */
object CrashDiagnostics {

    private const val FILE_NAME = "pending_crash.txt"
    private const val MAX_FRAMES_PER_THROWABLE = 24
    private const val MAX_CAUSES = 4
    private const val MAX_REPORT_CHARS = 16_384

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return

            val crashFile = File(context.applicationContext.filesDir, FILE_NAME)
            consume(crashFile)?.let { report ->
                DiagnosticLog.error("crash", "Previous run ended unexpectedly\n$report")
            }

            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    persist(crashFile, format(throwable))
                } finally {
                    if (previous != null) {
                        previous.uncaughtException(thread, throwable)
                    } else {
                        Process.killProcess(Process.myPid())
                    }
                }
            }
            installed = true
        }
    }

    /**
     * Exception messages are deliberately excluded: network failures commonly echo a
     * request URL, and an Xtream URL contains the account credentials.
     */
    internal fun format(throwable: Throwable): String {
        val lines = mutableListOf<String>()
        var current: Throwable? = throwable
        var causeIndex = 0
        while (current != null && causeIndex < MAX_CAUSES) {
            val failure = current
            lines += if (causeIndex == 0) {
                "Unhandled ${failure.javaClass.name}"
            } else {
                "Caused by ${failure.javaClass.name}"
            }
            failure.stackTrace.take(MAX_FRAMES_PER_THROWABLE).forEach { frame ->
                lines += "at $frame"
            }
            current = failure.cause
            causeIndex++
        }
        return lines.joinToString("\n").take(MAX_REPORT_CHARS)
    }

    private fun persist(file: File, report: String) {
        runCatching {
            val bytes = report.toByteArray(Charsets.UTF_8)
            FileOutputStream(file, false).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun consume(file: File): String? = runCatching {
        if (!file.isFile) return@runCatching null
        val report = file.readText(Charsets.UTF_8).take(MAX_REPORT_CHARS)
        file.delete()
        report.takeIf { it.isNotBlank() }
    }.getOrNull()
}