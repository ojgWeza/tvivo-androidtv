package com.dev.Tvivo.spike

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.DefaultAppColors
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType
import com.dev.Tvivo.ui.theme.TvivoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText

@OptIn(ExperimentalTestApi::class)
class M3CandidateSpikeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun pressBack() = composeRule.runOnUiThread {
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
    }

    private val poster = CandidatePoster("Sample Film", "HD", 0.4f, true)

    @Test fun tvArrowsOkBackAndSingleAccentSoftFrame() {
        var homeActivations = 0
        var posterActivations = 0
        var minimumEnforced: Boolean? = null
        composeRule.setContent {
            M3CandidateSpike(
                poster = poster,
                isTelevision = true,
                onHomeActivate = { homeActivations++ },
                onPosterActivate = { posterActivations++ },
                onMinimumTargetEnforcementObserved = { minimumEnforced = it }
            )
        }

        composeRule.runOnIdle { assertEquals(false, minimumEnforced) }
        composeRule.onNodeWithTag("candidate-home")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithTag("candidate-home").assertIsFocused()
        assertSingleRing("candidate-home")

        composeRule.onNodeWithTag("candidate-home")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("candidate-poster").assertIsFocused()
        assertSingleRing("candidate-poster")
        composeRule.onNodeWithTag("candidate-poster")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertEquals(1, posterActivations) }
        composeRule.onNodeWithTag("candidate-detail").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag("candidate-poster").assertIsFocused()
        assertSingleRing("candidate-poster")

        composeRule.onNodeWithTag("candidate-poster")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag("candidate-home").assertIsFocused()
        composeRule.onNodeWithTag("candidate-home")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle {
            assertEquals(1, homeActivations)
            assertEquals(1, posterActivations)
        }
    }

    @Test fun touchActivatesBothCardsWithoutGestureShimAndKeepsTouchTargets() {
        var homeActivations = 0
        var posterActivations = 0
        var minimumEnforced: Boolean? = null
        composeRule.setContent {
            M3CandidateSpike(
                poster = poster,
                isTelevision = false,
                onHomeActivate = { homeActivations++ },
                onPosterActivate = { posterActivations++ },
                onMinimumTargetEnforcementObserved = { minimumEnforced = it }
            )
        }

        composeRule.runOnIdle { assertEquals(true, minimumEnforced) }
        for (tag in listOf("candidate-home", "candidate-poster")) {
            val card = composeRule.onNodeWithTag(tag)
            card.assertHasClickAction()
            val bounds = card.getUnclippedBoundsInRoot()
            assertTrue("$tag width", bounds.width >= 48.dp)
            assertTrue("$tag height", bounds.height >= 48.dp)
            card.performTouchInput { click() }
            composeRule.onNodeWithTag("candidate-detail").assertIsDisplayed()
            pressBack()
        }
        composeRule.runOnIdle {
            assertEquals(1, homeActivations)
            assertEquals(1, posterActivations)
        }
    }

    @Test fun rtlMirrorsPhysicalPositionsAndArrowTraversal() {
        var posterActivations = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                M3CandidateSpike(
                    poster = poster,
                    isTelevision = true,
                    onHomeActivate = {},
                    onPosterActivate = { posterActivations++ }
                )
            }
        }

        val homeBounds = composeRule.onNodeWithTag("candidate-home").getUnclippedBoundsInRoot()
        val posterBounds = composeRule.onNodeWithTag("candidate-poster").getUnclippedBoundsInRoot()
        assertTrue("Home is at start in RTL", homeBounds.left > posterBounds.left)
        composeRule.onNodeWithTag("candidate-home")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithTag("candidate-home")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag("candidate-poster").assertIsFocused()
        assertSingleRing("candidate-poster")
        composeRule.onNodeWithTag("candidate-poster")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertEquals(1, posterActivations) }
    }

    @Test fun textMetricsAndColorRolesMatchTheCurrentTvTheme() {
        var tvHomeLayout: TextLayoutResult? = null
        var tvPosterLayout: TextLayoutResult? = null
        var m3HomeLayout: TextLayoutResult? = null
        var m3PosterLayout: TextLayoutResult? = null
        var tvPrimary: androidx.compose.ui.graphics.Color? = null
        var tvOutline: androidx.compose.ui.graphics.Color? = null
        var m3Scheme: ColorScheme? = null

        composeRule.setContent {
            TvivoTheme {
                tvPrimary = TvMaterialTheme.colorScheme.primary
                tvOutline = TvMaterialTheme.colorScheme.border
                Column {
                    TvText(
                        "Movies", color = AppColors.Ink, style = TvType.headline,
                        onTextLayout = { tvHomeLayout = it }
                    )
                    TvText(
                        poster.title, color = AppColors.Ink, style = TvType.label,
                        modifier = Modifier.width(98.dp),
                        maxLines = 2,
                        softWrap = true,
                        onTextLayout = { tvPosterLayout = it }
                    )
                    M3CandidateSpike(
                        poster = poster,
                        isTelevision = true,
                        onHomeActivate = {},
                        onPosterActivate = {},
                        onHomeTextLayout = { m3HomeLayout = it },
                        onPosterTextLayout = { m3PosterLayout = it },
                        onThemeObserved = { m3Scheme = it }
                    )
                }
            }
        }
        composeRule.runOnIdle {
            assertSameMetrics(tvHomeLayout!!, m3HomeLayout!!)
            assertSameMetrics(tvPosterLayout!!, m3PosterLayout!!)
            assertEquals(tvPrimary, m3Scheme!!.primary)
            assertEquals(tvOutline, m3Scheme!!.outline)
            assertEquals(DefaultAppColors.line, m3Scheme!!.outlineVariant)
            assertEquals(DefaultAppColors.ink, m3Scheme!!.onSurface)
            assertEquals(DefaultAppColors.elevated, m3Scheme!!.surface)
        }
        composeRule.onNodeWithTag("candidate-poster")
            .assert(hasContentDescription("Sample Film, HD, favourite"))
    }

    private fun assertSameMetrics(expected: TextLayoutResult, actual: TextLayoutResult) {
        // Box width depends on each candidate's parent constraints, not on text metrics; the
        // text itself must agree on height, line count, baselines and style.
        assertEquals(expected.size.height, actual.size.height)
        assertEquals(expected.lineCount, actual.lineCount)
        assertEquals(expected.firstBaseline, actual.firstBaseline, 0.01f)
        assertEquals(expected.lastBaseline, actual.lastBaseline, 0.01f)
        assertEquals(expected.layoutInput.style.fontSize, actual.layoutInput.style.fontSize)
        assertEquals(expected.layoutInput.style.lineHeight, actual.layoutInput.style.lineHeight)
        assertEquals(expected.layoutInput.style.color, actual.layoutInput.style.color)
        assertEquals(expected.layoutInput.style.platformStyle, actual.layoutInput.style.platformStyle)
    }

    private fun assertSingleRing(focusedTag: String) {
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodes(isFocused()).fetchSemanticsNodes().size)
        val otherTag = if (focusedTag == "candidate-home") "candidate-poster" else "candidate-home"
        composeRule.onNodeWithTag(otherTag).assertIsNotFocused()
        assertEquals(Palette.AccentSoft.toArgb(), topBorderColor(focusedTag))
        assertTrue(topBorderColor(otherTag) != Palette.AccentSoft.toArgb())
    }

    private fun topBorderColor(tag: String): Int {
        val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, 1].toArgb()
    }
}
