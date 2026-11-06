package com.dev.Tvivo.download

import com.dev.Tvivo.data.local.entities.DownloadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlaybackResolverTest {
    private fun row(state: String) = DownloadEntity("acct", "movie", 0, "42", state = state,
        relativePath = "movie/42.mp4", createdAt = 1, updatedAt = 1)

    @Test fun completedExistingFileResolvesToFileUrl() {
        assertEquals("file:///data/movie.mp4", DownloadPlaybackResolver.resolve(row("COMPLETED"), "file:///data/movie.mp4"))
    }

    @Test fun completedMissingFileMarksRowAndReturnsNull() {
        var marked = false
        assertNull(DownloadPlaybackResolver.resolve(row("COMPLETED"), null) { marked = true })
        assertTrue(marked)
    }

    @Test fun nonCompletedRowDoesNotResolveOrMarkMissing() {
        var marked = false
        assertNull(DownloadPlaybackResolver.resolve(row("QUEUED"), null) { marked = true })
        assertEquals(false, marked)
    }
}
