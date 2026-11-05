package com.dev.Tvivo.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPlannerTest {
    private val gb = DownloadQuota.BYTES_PER_GB

    @Test fun fitsAndExactlyAtCapAreReady() {
        assertEquals(DownloadPlanner.Plan.Ready, DownloadPlanner.plan(listOf(DownloadPlanner.Item(gb, true)), 2 * gb, gb, 0))
        assertEquals(DownloadPlanner.Plan.Ready, DownloadPlanner.plan(listOf(DownloadPlanner.Item(gb, true)), gb, 0, 0))
    }
    @Test fun overCapCarriesWarningNumbers() {
        assertEquals(DownloadPlanner.Plan.NeedsConfirmation(2 * gb, gb, gb, 1),
            DownloadPlanner.plan(listOf(DownloadPlanner.Item(2 * gb, true)), gb, 0, 0))
    }
    @Test fun unknownItemBlocksWholeBatch() {
        assertEquals(DownloadPlanner.Plan.Blocked(1), DownloadPlanner.plan(listOf(
            DownloadPlanner.Item(gb, true), DownloadPlanner.Item(null, false)), 10 * gb, 0, 0))
    }
    @Test fun unknownSingleItemIsBlocked() {
        assertEquals(DownloadPlanner.Plan.Blocked(1), DownloadPlanner.plan(
            listOf(DownloadPlanner.Item(null, false)), 10 * gb, 0, 0))
    }
    @Test fun estimateUsesConservativeReservation() {
        assertEquals(DownloadPlanner.Plan.NeedsConfirmation(3 * gb / 2, gb, gb / 2, 1),
            DownloadPlanner.plan(listOf(DownloadPlanner.Item(gb, false)), gb, 0, 0))
    }
    @Test fun allAlreadyPresentIsReported() {
        assertEquals(DownloadPlanner.Plan.AlreadyQueuedOrDone,
            DownloadPlanner.plan(listOf(DownloadPlanner.Item(gb, true, true)), gb, 0, 0))
    }
    @Test fun alreadyQueuedItemIsIgnoredAndItsReservationRemainsInUsage() {
        val queued = DownloadPlanner.Item(null, false, alreadyQueuedOrDone = true)
        assertEquals(DownloadPlanner.Plan.AlreadyQueuedOrDone,
            DownloadPlanner.plan(listOf(queued), gb, 0, gb))
        assertEquals(DownloadPlanner.Plan.Ready,
            DownloadPlanner.plan(listOf(queued, DownloadPlanner.Item(gb, true)), 2 * gb, 0, 0))
    }
    @Test fun alreadyCompletedItemIsIgnored() {
        assertEquals(DownloadPlanner.Plan.AlreadyQueuedOrDone,
            DownloadPlanner.plan(listOf(DownloadPlanner.Item(null, false, alreadyQueuedOrDone = true)), gb, 0, 0))
    }
    @Test fun usedAndReservedBytesBothCountTowardWarning() {
        assertEquals(DownloadPlanner.Plan.NeedsConfirmation(3 * gb, 2 * gb, gb, 1),
            DownloadPlanner.plan(listOf(DownloadPlanner.Item(gb, true)), 2 * gb, gb, gb))
    }
    @Test fun loweredCapBelowUsageBlocksNewRequest() {
        assertEquals(DownloadPlanner.Plan.NeedsConfirmation(3 * gb, 2 * gb, gb, 1),
            DownloadPlanner.plan(listOf(DownloadPlanner.Item(gb, true)), 2 * gb, 2 * gb, 0))
    }
}
