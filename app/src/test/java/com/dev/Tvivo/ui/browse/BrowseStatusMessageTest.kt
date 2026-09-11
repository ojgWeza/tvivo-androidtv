package com.dev.Tvivo.ui.browse

import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.sync.CatalogSyncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseStatusMessageTest {
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
}