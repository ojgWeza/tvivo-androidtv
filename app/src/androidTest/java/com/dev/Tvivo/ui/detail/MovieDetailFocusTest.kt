package com.dev.Tvivo.ui.detail

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.dev.Tvivo.ui.adaptive.AdaptiveEnvironment
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compile-only until the owner approves emulator or device testing. */
class MovieDetailFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun primaryButtonOwnsFocusAndPlaysAtSavedPosition() {
        var launched = -1L
        rule.setContent {
            AdaptiveEnvironment(televisionOverride = true, widthOverrideDp = 960) {
                TvivoTheme {
                    ItemDetailScreen(ContentType.MOVIES, 1, onPlay = { ms, _, _, _ -> launched = ms },
                        onOpenEpisodes = {}, stateOverride = ItemDetailUiState(title = "Film",
                            resumeFromMs = 120_000, isLoading = false))
                }
            }
        }
        rule.onNodeWithTag("detail-play").assertIsFocused()
        rule.onNodeWithTag("detail-play").performClick()
        rule.runOnIdle { assertEquals(120_000L, launched) }
    }
}
