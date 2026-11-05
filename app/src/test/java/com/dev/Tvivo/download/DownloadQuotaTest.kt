package com.dev.Tvivo.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQuotaTest {
    private val gb = DownloadQuota.BYTES_PER_GB

    @Test fun estimatesReserveFiftyPercent() {
        assertEquals(1_500L, DownloadQuota.conservativeEstimate(1_000))
    }

    @Test fun unknownSizeBlocks() {
        assertEquals(DownloadQuota.Evaluation.SizeUnknown,
            DownloadQuota.evaluate(listOf(DownloadQuota.ItemSize(null, false)), gb, 0, 0))
    }

    @Test fun exactBoundaryFitsAndOneByteOverDoesNot() {
        assertEquals(DownloadQuota.Evaluation.Fits(gb, gb),
            DownloadQuota.evaluate(listOf(DownloadQuota.ItemSize(gb, true)), gb, 0, 0))
        assertTrue(DownloadQuota.evaluate(listOf(DownloadQuota.ItemSize(gb + 1, true)), gb, 0, 0) is DownloadQuota.Evaluation.ExceedsCap)
    }

    @Test fun batchSumsAndCapBelowUsageBlocksEvenAnEmptyStart() {
        assertEquals(DownloadQuota.Evaluation.Fits(gb, gb), DownloadQuota.evaluate(
            listOf(DownloadQuota.ItemSize(gb / 2, true), DownloadQuota.ItemSize(gb / 2, true)), gb, 0, 0))
        assertTrue(DownloadQuota.evaluate(emptyList(), gb, gb + 1, 0) is DownloadQuota.Evaluation.ExceedsCap)
    }

    @Test fun capClampsAndDefaultsToTwenty() {
        assertEquals(20, DownloadQuota.DEFAULT_CAP_GB)
        assertEquals(1, DownloadQuota.clampCapGb(0))
        assertEquals(1, DownloadQuota.clampCapGb(1))
        assertEquals(500, DownloadQuota.clampCapGb(500))
        assertEquals(500, DownloadQuota.clampCapGb(501))
    }

    @Test fun settingsUsageShowsUsedAndReservedDecimalGbAndClampedCap() {
        assertEquals(DownloadQuota.Usage(1.25, 1, 0.5, true),
            DownloadQuota.usage(1_250_000_000L, 500_000_000L, 0))
        assertEquals(DownloadQuota.Usage(0.0, 500, 0.0, false), DownloadQuota.usage(-1, -1, 900))
    }
}
