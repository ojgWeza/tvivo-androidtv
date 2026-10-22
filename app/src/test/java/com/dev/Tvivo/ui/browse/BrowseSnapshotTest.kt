package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.ui.BrowseUiHarness
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.theme.TvivoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.dev.Tvivo.home.CatalogSort

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BrowseSnapshotTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi")
    fun tvBrowse720pFontScale1() = captureBrowse(1f)

    @Test @Config(qualifiers = "w1280dp-h720dp-mdpi")
    fun tvBrowse720pFontScale15() = captureBrowse(1.5f)

    @Test @Config(qualifiers = "w1920dp-h1080dp-mdpi")
    fun tvBrowse1080pFontScale1() = captureBrowse(1f)

    @Test @Config(qualifiers = "w1920dp-h1080dp-mdpi")
    fun tvBrowse1080pFontScale15() = captureBrowse(1.5f)

    @Test @Config(qualifiers = "w390dp-h844dp-mdpi")
    fun phoneCategoryPickerFontScale1() = capturePhonePicker(1f)

    @Test @Config(qualifiers = "w390dp-h844dp-mdpi")
    fun phoneCategoryPickerFontScale15() = capturePhonePicker(1.5f)

    private fun captureBrowse(fontScale: Float) {
        compose.setContent { BrowseUiHarness(television = true, fontScale = fontScale) }
        compose.onNodeWithTag("snapshot-root").captureRoboImage()
    }

    private fun capturePhonePicker(fontScale: Float) {
        compose.setContent {
            val rail = remember { FocusRequester() }
            val grid = remember { FocusRequester() }
            val density = androidx.compose.ui.platform.LocalDensity.current
            TvivoTheme {
                CompositionLocalProvider(
                    LocalIsTelevision provides false,
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale)
                ) {
                    Box(Modifier.fillMaxSize().testTag("snapshot-root")) {
                        CategoryPickerOverlay(visible = true, onDismiss = {}) { drawer ->
                            CategoryRail(
                                categories = listOf(
                                    CategoryEntity("fixture", "vod", "all", "All titles", 0),
                                    CategoryEntity("fixture", "vod", "ramadan", "RAMADAN EGYPT 2026 SD", 1)
                                ),
                                counts = mapOf("all" to 180, "ramadan" to 24),
                                selectedCategoryId = "ramadan", filter = "", onFilterChanged = {},
                                onSelect = {}, gridFocusRequester = grid, railFocusRequester = rail,
                                listState = rememberLazyListState(), gridAvailable = false,
                                categorySort = CatalogSort.MostVisited, fullScreen = true, modifier = drawer
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("snapshot-root").captureRoboImage()
    }
}
