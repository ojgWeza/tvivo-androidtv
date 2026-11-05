package com.dev.Tvivo.ui.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.dev.Tvivo.download.DownloadQuota
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.theme.TvivoTheme
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.series.SeriesDetailUiState
import com.dev.Tvivo.ui.series.SeriesEpisodeList
import com.dev.Tvivo.ui.series.SeasonTab
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import androidx.compose.ui.Modifier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadControlsComposeTest {
    @get:Rule val compose = createComposeRule()

    private val warning = DownloadQuota.Evaluation.ExceedsCap(3_000_000_000L, 2_000_000_000L, 1_000_000_000L)

    @Test fun warningDialogHasOnlyCancelAndSettingsActions() {
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides false) {
                    ItemDetailScreen(ContentType.MOVIES, 4, onPlay = { _, _, _, _ -> }, onOpenEpisodes = {},
                        stateOverride = ItemDetailUiState(title = "Movie", fileSize = FileSizeDisplay("1 GB", false), downloadWarning = warning))
                }
            }
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Change limit in Settings").assertIsDisplayed()
        compose.onNodeWithText("Download anyway").assertDoesNotExist()
    }

    @Test fun televisionDoesNotShowDownloadControls() {
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides true) {
                    ItemDetailScreen(ContentType.MOVIES, 4, onPlay = { _, _, _, _ -> }, onOpenEpisodes = {},
                        stateOverride = ItemDetailUiState(title = "Movie", fileSize = FileSizeDisplay("1 GB", false)))
                }
            }
        }
        compose.onNodeWithTag("movie-download").assertDoesNotExist()
        compose.onNodeWithText("Queue download").assertDoesNotExist()
    }

    @Test fun unknownMovieSizeHidesQueueActionAndOffersStreamLabel() {
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides false) {
                    ItemDetailScreen(ContentType.MOVIES, 4, onPlay = { _, _, _, _ -> }, onOpenEpisodes = {},
                        stateOverride = ItemDetailUiState(title = "Movie"))
                }
            }
        }
        compose.onNodeWithTag("movie-download").assertDoesNotExist()
        compose.onNodeWithText("Size unknown — stream instead").assertExists()
    }

    @Test fun seriesDownloadControlsArePhoneOnly() {
        val episode = EpisodeEntity("acct", 8, "episode-1", 1, 1, "Episode", "mp4", 100, null)
        val state = SeriesDetailUiState(seasons = listOf(SeasonTab(1, "Season 1", 1)), selectedSeason = 1,
            episodes = listOf(episode), fileSizes = mapOf("episode-1" to FileSizeDisplay("1 GB", false)))
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides true) {
                    SeriesEpisodeList(state, { null }, {}, { _, _ -> }, {}, {}, {}, {}, {}, null, Modifier)
                }
            }
        }
        compose.onNodeWithTag("download-season").assertDoesNotExist()
        compose.onNodeWithTag("episode-download-episode-1").assertDoesNotExist()
    }
}
