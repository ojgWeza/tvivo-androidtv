package com.dev.Tvivo.ui.browse

import com.dev.Tvivo.data.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseStatusMessageTest {
    @Test fun rateLimitAndServerShutdownHaveDistinctCachedCopy() {
        assertEquals("Server busy — showing cached data. Try again shortly",
            browseStatusMessage(AppError.RateLimited, LibrarySyncState.Idle, hasCachedItems = true))
        assertEquals("Server unavailable — showing cached data",
            browseStatusMessage(AppError.ServiceUnavailable, LibrarySyncState.Idle, hasCachedItems = true))
    }
    @Test
    fun networkFailureWithCachedRowsIsExplicitlyOffline() {
        assertEquals(
            "Offline — showing cached data",
            browseStatusMessage(AppError.Unreachable, LibrarySyncState.Idle, hasCachedItems = true)
        )
    }

    @Test
    fun networkFailureWithoutCachedRowsStaysAnErrorState() {
        assertNull(browseStatusMessage(AppError.Unreachable, LibrarySyncState.Idle, hasCachedItems = false))
    }

    @Test
    fun partialCatalogIsDistinctFromNormalBrowseState() {
        assertEquals(
            "Catalog incomplete — showing available categories",
            browseStatusMessage(null, LibrarySyncState.Partial, hasCachedItems = false)
        )
    }

    @Test
    fun failedCatalogSyncWithRowsExplainsCachedFallback() {
        assertEquals(
            "Catalog update unavailable — showing cached data",
            browseStatusMessage(null, LibrarySyncState.Failed, hasCachedItems = true)
        )
    }

    @Test
    fun liveNetworkErrorKeepsCachedDataStatusVisible() {
        assertEquals(
            "Offline — showing cached data",
            browseStatusMessage(AppError.Unreachable, LibrarySyncState.Failed, hasCachedItems = true)
        )
    }
}
