package com.dev.Tvivo.ui.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import com.dev.Tvivo.download.DownloadQuota
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.theme.TvivoTheme
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.series.SeriesDetailUiState
import com.dev.Tvivo.ui.series.SeriesEpisodeList
import com.dev.Tvivo.ui.series.SeasonTab
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import com.dev.Tvivo.download.DownloadPlaybackResolver
import com.dev.Tvivo.data.local.entities.DownloadEntity
import org.junit.Assert.assertEquals
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

    @Test fun downloadedMovieKeepsSinglePlayAndUsesResolvedLocalUrl() {
        var playedUrl: String? = null
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides false) {
                    ItemDetailScreen(ContentType.MOVIES, 42, onPlay = { _, _, _, _ -> }, onOpenEpisodes = {},
                        stateOverride = ItemDetailUiState(title = "Movie", isLoading = false, downloadState = "COMPLETED"),
                        playbackUrlOverride = "file:///data/movie.mp4", onPlayUrlOverride = { playedUrl = it })
                }
            }
        }
        compose.onNodeWithTag("detail-play").assertIsDisplayed()
        compose.onNodeWithTag("detail-play-downloaded").assertDoesNotExist()
        compose.onAllNodesWithTag("detail-play").assertCountEquals(1)
        compose.onNodeWithTag("detail-play").performClick()
        assertEquals("file:///data/movie.mp4", playedUrl)
        val row = DownloadEntity("acct", "movie", 0, "42", state = "COMPLETED", relativePath = "movie/42.mp4", createdAt = 1, updatedAt = 1)
        assertEquals("file:///data/movie.mp4", DownloadPlaybackResolver.resolve(row, "file:///data/movie.mp4"))
    }

    @Test fun notDownloadedMoviePlayStillUsesExistingStreamCallback() {
        var played = false
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides false) {
                    ItemDetailScreen(ContentType.MOVIES, 42, onPlay = { _, _, _, _ -> played = true }, onOpenEpisodes = {},
                        stateOverride = ItemDetailUiState(title = "Movie", isLoading = false))
                }
            }
        }
        compose.onNodeWithTag("detail-play").performClick()
        assertEquals(true, played)
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

    @Test fun seriesEpisodeRowsPlayViaTheirSingleRowControl() {
        val downloaded = EpisodeEntity("acct", 8, "episode-1", 1, 1, "Episode 1", "mp4", 100, null)
        val streaming = EpisodeEntity("acct", 8, "episode-2", 1, 2, "Episode 2", "mp4", 100, null)
        val state = SeriesDetailUiState(seasons = listOf(SeasonTab(1, "Season 1", 2)), selectedSeason = 1,
            episodes = listOf(downloaded, streaming), downloadStates = mapOf("episode-1" to "COMPLETED"))
        val played = mutableListOf<String>()
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides false) {
                    SeriesEpisodeList(state, { null }, {}, { _, _ -> }, {}, { played += it.episodeId }, {}, {}, {}, null, Modifier)
                }
            }
        }
        compose.onNodeWithTag("episode-episode-1").performClick()
        compose.onNodeWithTag("episode-episode-2").performClick()
        assertEquals(listOf("episode-1", "episode-2"), played)
        compose.onNodeWithTag("episode-play-downloaded-episode-1").assertDoesNotExist()
        val row = DownloadEntity("acct", "episode", 8, "episode-1", state = "COMPLETED", relativePath = "series/8/episode-1.mp4", createdAt = 1, updatedAt = 1)
        assertEquals("file:///data/episode-1.mp4", DownloadPlaybackResolver.resolve(row, "file:///data/episode-1.mp4"))
    }

    @Test fun seriesNextEpisodeWithoutDownloadFallsBackToStreamUrl() {
        val row = DownloadEntity("acct", "episode", 8, "episode-1", state = "COMPLETED", relativePath = "series/8/episode-1.mp4", createdAt = 1, updatedAt = 1)
        assertEquals("https://provider/episode-2.mkv", DownloadPlaybackResolver.resolveOrStream(
            row.copy(mediaId = "episode-2"), null, "https://provider/episode-2.mkv")
        )
    }

    @Test fun televisionSeriesRowsKeepDownloadControlsHidden() {
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
