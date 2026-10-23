package com.dev.Tvivo.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DriveScreensComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tvDeviceCodeKeepsCaseAndDpadReachesCancel() {
        var refreshed = 0
        var cancelled = 0
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides true) {
                    val input = LocalInputModeManager.current
                    SideEffect { input.requestInputMode(InputMode.Keyboard) }
                    DriveFlowScreen(DriveFlowState.DeviceCode("https://example.test/approve", "Ab-Cd9",
                        System.currentTimeMillis() + 60_000), { refreshed++ }, {}, {}, { cancelled++ })
                }
            }
        }
        compose.onNodeWithTag("drive-code-value").assertIsDisplayed()
        compose.onNodeWithTag("drive-refresh-code").requestFocus()
        compose.onNodeWithTag("drive-refresh-code").assertIsFocused()
        compose.onNodeWithTag("drive-refresh-code").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("drive-cancel").assertIsFocused().performClick()
        assertEquals(1, cancelled)
        assertEquals(0, refreshed)
    }

    @Test fun tvBackCancelsCodePage() {
        var cancelled = 0
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides true) {
                    DriveFlowScreen(DriveFlowState.DeviceCode("https://example.test", "Case-1",
                        System.currentTimeMillis() + 60_000), {}, {}, {}, { cancelled++ })
                }
            }
        }
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(1, cancelled)
    }

    @Test fun bootstrapFailureAndAccountActionsRenderOnPhone() {
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides false) {
                    DriveFlowScreen(DriveFlowState.Failed("Drive is full. No library files were changed."),
                        {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("drive-flow-error").assertIsDisplayed()
        compose.onNodeWithTag("drive-retry").assertIsDisplayed()
        compose.onNodeWithTag("drive-cancel").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w960dp-h540dp-mdpi") fun accountKeepsDisconnectLastAndConfirmed() {
        var disconnected = false
        compose.setContent {
            TvivoTheme {
                CompositionLocalProvider(LocalIsTelevision provides true) {
                    val input = LocalInputModeManager.current
                    LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
                    DriveAccountScreen(DriveAccount(SourceSession.Drive("drive:test", "permission"),
                        "Mock Name", "mock@example.test", "folder", 10L), false, null,
                        {}, {}, { disconnected = true })
                }
            }
        }
        compose.onNodeWithTag("drive-resync").assertIsDisplayed()
        compose.onNodeWithTag("drive-switch-source").assertExists()
        compose.onNodeWithTag("drive-disconnect").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-dialog-Keep connected").assertExists().performClick()
        compose.onNodeWithTag("confirm-dialog-Keep connected").assertDoesNotExist()
        assertTrue(!disconnected)
    }

    private fun SemanticsNodeInteraction.requestFocus() =
        performSemanticsAction(SemanticsActions.RequestFocus) { it() }
}
