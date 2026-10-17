package com.dev.Tvivo.auth

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** TV layout and typed-input baseline, using only the ViewModel's in-memory state. */
class LoginFocusCharacterizationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun fieldsKeepTypedInputAndActionsStayAboveTvImeCeiling() {
        lateinit var model: LoginViewModel
        composeRule.setContent {
            val context = LocalContext.current
            val television = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                    Configuration.UI_MODE_TYPE_TELEVISION
                smallestScreenWidthDp = 960
                screenWidthDp = 960
            }
            model = remember { LoginViewModel(context.applicationContext as Application) }
            CompositionLocalProvider(LocalConfiguration provides television) {
                TvivoTheme { LoginScreen(onAuthenticated = {}, viewModel = model) }
            }
        }
        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[0].assertIsFocused()
        fields[0].performTextInput("fixture.invalid")
        fields[0].performImeAction()
        fields[1].assertIsFocused()
        fields[1].performTextInput("fixture-user")
        fields[1].performImeAction()
        fields[2].assertIsFocused()
        fields[2].performTextInput("fixture-pass")
        // TV Material buttons do not complete a touch click, so use the button's own click action.
        composeRule.onNodeWithText("Show password").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        fields[0].assertTextContains("fixture.invalid")
        fields[1].assertTextContains("fixture-user")
        composeRule.runOnIdle {
            assertEquals("fixture-pass", model.state.value.password)
            assertEquals("fixture.invalid", model.state.value.server)
        }
        // Q-30 acceptance: every action stays above the 297 dp TV IME focus ceiling.
        listOf("Sign in", "Hide password", "Clear").forEach { label ->
            val bottomDp = composeRule.onNodeWithText(label).getBoundsInRoot().bottom.value
            assertTrue("$label below TV IME focus ceiling: $bottomDp", bottomDp <= 297f)
        }
    }
}
