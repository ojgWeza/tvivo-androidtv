package com.dev.Tvivo.ui.common

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DpadFieldNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rightEscapesToExplicitNeighbour() {
        val field = FocusRequester()
        val right = FocusRequester()

        composeRule.setContent {
            val focusManager = LocalFocusManager.current
            LaunchedEffect(Unit) { field.requestFocus() }
            Row {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(100.dp)
                        .testTag("field")
                        .focusRequester(field)
                        .focusProperties { this.right = right }
                        .dpadFieldNavigation(focusManager)
                        .focusable()
                )
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(100.dp)
                        .testTag("right")
                        .focusRequester(right)
                        .focusable()
                )
            }
        }

        composeRule.onNodeWithTag("field").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("right").assertIsFocused()
    }

    @Test
    fun downEscapesToExplicitNeighbour() {
        val field = FocusRequester()
        val down = FocusRequester()

        composeRule.setContent {
            val focusManager = LocalFocusManager.current
            LaunchedEffect(Unit) { field.requestFocus() }
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(100.dp)
                        .testTag("field")
                        .focusRequester(field)
                        .focusProperties { this.down = down }
                        .dpadFieldNavigation(focusManager)
                        .focusable()
                )
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(100.dp)
                        .testTag("down")
                        .focusRequester(down)
                        .focusable()
                )
            }
        }

        composeRule.onNodeWithTag("field").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("down").assertIsFocused()
    }
}