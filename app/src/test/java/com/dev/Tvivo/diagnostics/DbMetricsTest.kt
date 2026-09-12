package com.dev.Tvivo.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DbMetricsTest {

    @Test
    fun `directoryBytes includes nested files`() {
        val directory = Files.createTempDirectory("tvivo-db-metrics-").toFile()
        try {
            File(directory, "root").writeBytes(ByteArray(3))
            val nested = File(directory, "nested").apply { mkdir() }
            File(nested, "child").writeBytes(ByteArray(5))

            assertEquals(8L, DbMetrics.directoryBytes(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `snapshot includes database companions in its footprint`() {
        val snapshot = DbMetrics.Snapshot(
            databaseBytes = 10L,
            walBytes = 4L,
            sharedMemoryBytes = 2L,
            imageCacheBytes = 8L,
            tableBytes = emptyMap()
        )

        assertEquals(16L, snapshot.databaseFootprintBytes)
        assertEquals(24L, snapshot.totalBytes)
    }

    @Test
    fun `formatBytes uses readable binary units`() {
        assertEquals("1023 B", DbMetrics.formatBytes(1023))
        assertEquals("1.0 KB", DbMetrics.formatBytes(1024))
        assertTrue(DbMetrics.formatBytes(1024L * 1024).endsWith("MB"))
    }
}
