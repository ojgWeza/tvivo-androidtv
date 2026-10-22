package com.dev.Tvivo.ui.browse

import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.data.local.entities.CatalogSyncEntity
import com.dev.Tvivo.sync.CatalogSyncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseCatalogStateTest {
    private val account = "account-a"
    private val category = "category-1"

    @Test fun libraryProgressHasUnknownTotalUntilOneIsActuallyProvided() {
        val row = CatalogSyncEntity(account, "vod", CatalogSyncer.STATE_INDEXING, 45000, 0, 1)
        assertEquals(LibrarySyncState.Updating(45000, null), librarySyncState(row, account, "vod"))
        assertEquals(LibrarySyncState.Updating(45000, 50000),
            librarySyncState(row.copy(total = 50000), account, "vod"))
        assertEquals(LibrarySyncState.Complete(45000),
            librarySyncState(row.copy(state = CatalogSyncer.STATE_COMPLETE), account, "vod"))
    }

    @Test fun progressFromAnotherAccountOrContentTypeIsIgnored() {
        val row = CatalogSyncEntity(account, "vod", CatalogSyncer.STATE_INDEXING, 10, 0, 1)
        assertEquals(LibrarySyncState.Idle, librarySyncState(row, "account-b", "vod"))
        assertEquals(LibrarySyncState.Idle, librarySyncState(row, account, "series"))
    }

    @Test fun zeroPagingRowsDoNotProveCategoryIsEmpty() {
        val pending = CategoryFetchState.FirstFetchPending(account, category, 1)
        assertEquals(BrowseEmptyPresentation.Loading, present(pending))
        assertEquals(BrowseEmptyPresentation.Loading,
            present(CategoryFetchState.ConfirmedEmpty(account, category, 1),
                sync = LibrarySyncState.Updating(45000, null)))
        assertEquals(BrowseEmptyPresentation.Empty,
            present(CategoryFetchState.ConfirmedEmpty(account, category, 1)))
    }

    @Test fun cachedRowsRemainUsableDuringRefreshAndFailure() {
        val cached = CategoryFetchState.CachedRows(account, category, 2, 700, refreshing = true)
        assertEquals(BrowseEmptyPresentation.Rows, present(cached, hasRows = true))
        assertEquals(BrowseEmptyPresentation.Rows,
            present(cached.copy(refreshing = false, error = AppError.Unreachable), hasRows = true))
        assertEquals(BrowseEmptyPresentation.Loading, present(cached))
    }

    @Test fun filterMissAndStaleSelectionStayDistinct() {
        val empty = CategoryFetchState.ConfirmedEmpty(account, category, 3)
        assertEquals(BrowseEmptyPresentation.FilterMiss, present(empty, filtered = true))
        assertEquals(BrowseEmptyPresentation.Loading,
            browseEmptyPresentation(empty, account, "category-2", LibrarySyncState.Complete(10),
                hasRows = false, pagingLoading = false, filtered = false, virtual = false))
        assertEquals(BrowseEmptyPresentation.Loading,
            browseEmptyPresentation(empty, "account-b", category, LibrarySyncState.Complete(10),
                hasRows = false, pagingLoading = false, filtered = false, virtual = false))
    }

    @Test fun categoryFailureWithNoRowsOffersRecovery() {
        assertEquals(BrowseEmptyPresentation.Error,
            present(CategoryFetchState.Failed(account, category, 4, AppError.Unreachable)))
    }

    @Test fun olderRequestForSameCategoryCannotReplaceNewSelection() {
        val current = CategoryFetchState.FirstFetchPending(account, category, 7)
        assertTrue(current.matchesRequest(account, category, 7))
        assertFalse(current.matchesRequest(account, category, 6))
        assertFalse(current.matchesRequest("account-b", category, 7))
        assertFalse(current.matchesRequest(account, "category-2", 7))
    }

    private fun present(categoryState: CategoryFetchState,
                        sync: LibrarySyncState = LibrarySyncState.Complete(10),
                        hasRows: Boolean = false, filtered: Boolean = false) =
        browseEmptyPresentation(categoryState, account, category, sync, hasRows,
            pagingLoading = false, filtered = filtered, virtual = false)
}
