package com.dev.Tvivo.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeMetricsTest {

    @Test
    fun `memory values remain independently represented`() {
        val snapshot = RuntimeMetrics.Snapshot(
            javaUsedBytes = 1L,
            javaCommittedBytes = 2L,
            javaMaxBytes = 3L,
            nativeHeapBytes = 4L,
            totalPssBytes = 5L,
            privateDirtyBytes = 6L,
            gcCount = "7",
            gcTimeMs = "8"
        )

        assertEquals(3L, snapshot.javaMaxBytes)
        assertEquals(5L, snapshot.totalPssBytes)
        assertEquals("7", snapshot.gcCount)
    }
}
