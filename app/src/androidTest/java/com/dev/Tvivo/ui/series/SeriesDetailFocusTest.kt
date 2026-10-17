package com.dev.Tvivo.ui.series

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import com.dev.Tvivo.ui.adaptive.AdaptiveEnvironment
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compiled into androidTest; owner runs it after separately approving a device session. */
@OptIn(ExperimentalTestApi::class)
class SeriesDetailFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun selectorDownRestoresEpisodeAndClickPlaysIt() {
        var played: String? = null
        val ep = EpisodeEntity("a", 1, "ep", 1, 1, "Pilot", "mp4", 1800, null)
        rule.setContent {
            AdaptiveEnvironment(televisionOverride = true, widthOverrideDp = 960) {
                TvivoTheme {
                    SeriesEpisodeList(
                        state = SeriesDetailUiState(title = "Show", seasons = listOf(SeasonTab(1, "Season 1", 1)),
                            selectedSeason = 1, episodes = listOf(ep), isLoading = false),
                        focusedEpisodeInSeason = { "ep" }, onSelectSeason = {},
                        onFocusedEpisode = { _, _ -> }, onRetry = {},
                        onPlay = { played = it.episodeId }, onMenu = {}, header = null,
                        modifier = Modifier.testTag("test-series")
                    )
                }
            }
        }
        rule.onNodeWithTag("season-1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag("season-1").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("episode-ep").assertIsFocused()
        rule.onNodeWithTag("episode-ep").performClick()
        rule.runOnIdle { assertEquals("ep", played) }
    }
}
