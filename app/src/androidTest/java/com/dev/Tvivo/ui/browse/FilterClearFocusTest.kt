package com.dev.Tvivo.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
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
class FilterClearFocusTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun rightFromPopulatedFilterReachesClearAndClearsText() {
        var value by mutableStateOf("")
        composeRule.setContent {
            TvivoTheme {
                ItemFilterField(value, { value = it }, FocusRequester(),
                    FocusRequester(), FocusRequester(), FocusRequester(),
                    railAvailable = true, gridAvailable = true)
            }
        }
        composeRule.onNodeWithTag("item-filter-field").performTextInput("movie")
        composeRule.onNodeWithTag("item-filter-field").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("item-filter-clear").assertIsFocused().performClick()
        composeRule.onNodeWithTag("item-filter-clear").assertDoesNotExist()
    }
}
