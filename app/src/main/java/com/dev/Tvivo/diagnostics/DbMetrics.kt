package com.dev.Tvivo.diagnostics

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.ui.common.TvivoImageLoader
import java.io.File
import java.util.Locale

/**
 * Storage facts for Diagnostics. These are deliberately measurements, not a cleanup
 * policy: cache and database limits need evidence from the physical TV before changing
 * either one.
 */
object DbMetrics {

    data class Snapshot(
        val databaseBytes: Long,
        val walBytes: Long,
        val sharedMemoryBytes: Long,
        val imageCacheBytes: Long,
        /** Empty when this device's SQLite build does not include the optional dbstat table. */
        val tableBytes: Map<String, Long>
    ) {
        val databaseFootprintBytes: Long
            get() = databaseBytes + walBytes + sharedMemoryBytes
        val totalBytes: Long
            get() = databaseFootprintBytes + imageCacheBytes
    }

    fun collect(context: Context, database: SupportSQLiteDatabase? = null): Snapshot {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        return Snapshot(
            databaseBytes = dbFile.lengthIfFile(),
            walBytes = File("${dbFile.path}-wal").lengthIfFile(),
            sharedMemoryBytes = File("${dbFile.path}-shm").lengthIfFile(),
            imageCacheBytes = directoryBytes(context.cacheDir.resolve(TvivoImageLoader.DISK_CACHE_DIRECTORY)),
            tableBytes = database?.let(::readTableBytes).orEmpty()
        )
    }

    /** dbstat is optional in SQLite builds, so diagnostics degrade to file totals safely. */
    private fun readTableBytes(database: SupportSQLiteDatabase): Map<String, Long> = runCatching {
        database.query(
            "SELECT name, SUM(pgsize) FROM dbstat " +
                "WHERE name NOT LIKE 'sqlite_%' GROUP BY name ORDER BY name"
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), cursor.getLong(1))
                }
            }
        }
    }.getOrDefault(emptyMap())

    internal fun directoryBytes(file: File): Long = when {
        file.isFile -> file.length()
        !file.isDirectory -> 0L
        else -> file.listFiles()?.sumOf(::directoryBytes) ?: 0L
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < KIB -> "$bytes B"
        bytes < MIB -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / KIB)
        else -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / MIB)
    }

    private fun File.lengthIfFile(): Long = if (isFile) length() else 0L

    private const val KIB = 1024L
    private const val MIB = KIB * KIB
}
