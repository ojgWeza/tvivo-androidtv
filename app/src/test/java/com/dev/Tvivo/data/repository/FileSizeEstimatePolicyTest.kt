package com.dev.Tvivo.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileSizeEstimatePolicyTest {
    @Test fun estimates_decimal_bytes_and_accepts_in_range_values() {
        assertEquals(180_000_000L, FileSizeEstimatePolicy.estimateBytes(2400, 600))
        assertEquals(180_000_000_000L, FileSizeEstimatePolicy.estimateBytes(50_000, 28_800))
    }

    @Test fun rejects_implausible_inputs_and_out_of_range_estimates() {
        listOf(
            null to 600, 127 to 600, 80_001 to 600,
            1000 to 59, 1000 to 28_801, 128 to 60, 80_000 to 28_800
        ).forEach { (bitrate, duration) ->
            assertNull(FileSizeEstimatePolicy.estimateBytes(bitrate, duration))
        }
        assertNull(FileSizeEstimatePolicy.estimateBytes(1000, null))
    }
}
