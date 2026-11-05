package com.dev.Tvivo.ui.downloads

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.CompositionLocalProvider
import com.dev.Tvivo.data.local.entities.DownloadEntity
import com.dev.Tvivo.download.DownloadQuota
import com.dev.Tvivo.download.DownloadQuota.DEFAULT_CAP_GB
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun row(state: String, kind: String = "movie", reason: String? = null, mediaId: String = "8") = DownloadEntity(
        accountId = "account", kind = kind, seriesId = if (kind == "episode") 40 else 0,
        mediaId = mediaId, state = state, expectedBytes = 1_000_000, partialBytes = 250_000,
        reservedBytes = 0, createdAt = 1, updatedAt = 1, failureReason = reason
    )

    @Test fun groupingSeparatesCompletedFromActiveAndFailed() {
        val rows = listOf(
            DownloadDisplayRow(row("QUEUED"), "Queued movie"),
            DownloadDisplayRow(row("FAILED", reason = "authorization", mediaId = "9"), "Failed movie"),
            DownloadDisplayRow(row("COMPLETED", mediaId = "10"), "Done movie")
        )
        val (active, completed) = groupDownloads(rows)
        assertEquals(listOf("QUEUED", "FAILED"), active.map { it.row.state })
        assertEquals(listOf("COMPLETED"), completed.map { it.row.state })
        assertEquals(DownloadQuota.Usage(0.25, DEFAULT_CAP_GB, 0.0, false),
            DownloadQuota.usage(250_000_000, 0, DEFAULT_CAP_GB))
    }

    @Test fun downloadsAreUnavailableOnTelevision() {
        assertEquals(false, com.dev.Tvivo.downloadsAvailable(true))
        assertEquals(true, com.dev.Tvivo.downloadsAvailable(false))
    }

    @Test fun failedRowShowsReasonAndRetryAction() {
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides false) {
                    DownloadRows(listOf(DownloadDisplayRow(row("FAILED", reason = "authorization"), "Film")),
                        onAction = { _, _ -> }, onDeleteSeries = {}, onPlayDownloaded = {})
                }
            }
        }
        compose.onNodeWithText("Download stopped: authorization was rejected. Sign in again or retry later.").assertIsDisplayed()
        compose.onNodeWithTag("retry-8").assertIsDisplayed()
    }

    @Test fun completedPlayActionIsAvailableOnlyForCompletedRows() {
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides false) {
                    DownloadRows(listOf(DownloadDisplayRow(row("COMPLETED"), "Film")),
                        onAction = { _, _ -> }, onDeleteSeries = {}, onPlayDownloaded = {})
                }
            }
        }
        compose.onNodeWithTag("play-8").assertIsDisplayed()
        compose.onNodeWithTag("delete-8").assertIsDisplayed()
    }

    @Test fun activePlayActionIsHidden() {
        compose.setContent { TvivoTheme { CompositionLocalProvider(LocalIsTelevision provides false) {
            DownloadRows(listOf(DownloadDisplayRow(row("QUEUED"), "Film")), onAction = { _, _ -> }, onDeleteSeries = {}, onPlayDownloaded = {})
        } } }
        compose.onNodeWithTag("play-8").assertDoesNotExist()
    }

    @Test fun emptyDownloadsShowsEmptyState() {
        compose.setContent { TvivoTheme { DownloadsEmptyState() } }
        compose.onNodeWithTag("downloads-empty").assertIsDisplayed()
        compose.onNodeWithText("No downloads yet").assertIsDisplayed()
    }
}
