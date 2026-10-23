package com.dev.Tvivo.ui.shell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SearchClearFocusTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun rightFromPopulatedSearchReachesClearAndClearsText() {
        composeRule.setContent {
            TvivoTheme {
                SearchScreen(
                    accountId = "test-account",
                    query = "",
                    onQueryChanged = {},
                    onOpen = {},
                    onBrowse = {}
                )
            }
        }
        composeRule.onNodeWithTag("search-field").performTextInput("movie")
        composeRule.onNodeWithTag("search-field").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("search-clear").assertIsFocused().performClick()
        composeRule.onNodeWithTag("search-clear").assertDoesNotExist()
    }
}
