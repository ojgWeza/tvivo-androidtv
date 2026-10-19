package com.dev.Tvivo.ui.browse

import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.sync.CatalogSyncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseStatusMessageTest {
    @Test fun rateLimitAndServerShutdownHaveDistinctCachedCopy() {
        assertEquals("Server busy — showing cached data. Try again shortly",
            browseStatusMessage(AppError.RateLimited, null, hasCachedItems = true))
        assertEquals("Server unavailable — showing cached data",
            browseStatusMessage(AppError.ServiceUnavailable, null, hasCachedItems = true))
    }
    @Test
    fun networkFailureWithCachedRowsIsExplicitlyOffline() {
        assertEquals(
            "Offline — showing cached data",
            browseStatusMessage(AppError.Unreachable, null, hasCachedItems = true)
        )
    }

    @Test
    fun networkFailureWithoutCachedRowsStaysAnErrorState() {
        assertNull(browseStatusMessage(AppError.Unreachable, null, hasCachedItems = false))
    }

    @Test
    fun partialCatalogIsDistinctFromNormalBrowseState() {
        assertEquals(
            "Catalog incomplete — showing available categories",
            browseStatusMessage(null, CatalogSyncer.STATE_PARTIAL, hasCachedItems = false)
        )
    }

    @Test
    fun failedCatalogSyncWithRowsExplainsCachedFallback() {
        assertEquals(
            "Catalog update unavailable — showing cached data",
            browseStatusMessage(null, CatalogSyncer.STATE_FAILED, hasCachedItems = true)
        )
    }

    @Test
    fun liveNetworkErrorKeepsCachedDataStatusVisible() {
        assertEquals(
            "Offline — showing cached data",
            browseStatusMessage(AppError.Unreachable, CatalogSyncer.STATE_FAILED, hasCachedItems = true)
        )
    }
}
