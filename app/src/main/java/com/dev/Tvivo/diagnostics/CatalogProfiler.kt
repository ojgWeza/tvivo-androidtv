package com.dev.Tvivo.diagnostics

import android.os.SystemClock
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Runs representative local-only catalog reads on demand. It deliberately does not log
 * individual samples: query timing is useful while diagnosing a slow device, but would
 * make a healthy session's persistent Diagnostics record noisy.
 */
object CatalogProfiler {

    data class Sample(
        val label: String,
        val elapsedMs: Long,
        val rowsRead: Int,
        val queryPlan: String
    )

    data class Snapshot(val samples: List<Sample>)

    fun profile(database: SupportSQLiteDatabase): Snapshot {
        val accountId = database.query(
            "SELECT accountId FROM vod_streams UNION SELECT accountId FROM live_streams " +
                "UNION SELECT accountId FROM series LIMIT 1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return Snapshot(emptyList())

        return Snapshot(
            CONTENT_TABLES.flatMap { table ->
                listOf(
                    sample(database, "$table rows", "SELECT COUNT(*) FROM $table WHERE accountId = ?", arrayOf(accountId)),
                    sample(
                        database,
                        "$table first page",
                        "SELECT 1 FROM $table WHERE accountId = ? ORDER BY nameNormalized ASC LIMIT 50",
                        arrayOf(accountId)
                    ),
                    sample(
                        database,
                        "$table filtered count",
                        "SELECT COUNT(*) FROM $table WHERE accountId = ? AND nameNormalized LIKE '%' || ? || '%'",
                        arrayOf(accountId, "a")
                    ),
                    sample(
                        database,
                        "$table rail counts",
                        "SELECT categoryId, COUNT(*) FROM $table WHERE accountId = ? GROUP BY categoryId",
                        arrayOf(accountId)
                    )
                )
            }
        )
    }

    private fun sample(
        database: SupportSQLiteDatabase,
        label: String,
        sql: String,
        args: Array<String>
    ): Sample {
        val start = SystemClock.elapsedRealtimeNanos()
        val rows = database.query(sql, args).use { cursor ->
            var count = 0
            while (cursor.moveToNext()) count++
            count
        }
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / NANOS_PER_MILLISECOND
        val plan = runCatching {
            database.query("EXPLAIN QUERY PLAN $sql", args).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.columnCount - 1))
                }.joinToString("; ")
            }
        }.getOrDefault("Query plan unavailable")
        return Sample(label, elapsedMs, rows, plan)
    }

    private val CONTENT_TABLES = listOf("vod_streams", "live_streams", "series")
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
