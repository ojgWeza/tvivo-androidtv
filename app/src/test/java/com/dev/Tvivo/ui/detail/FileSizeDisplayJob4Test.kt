package com.dev.Tvivo.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import com.dev.Tvivo.data.local.entities.MediaFileSizeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileSizeDisplayJob4Test {
    @get:Rule val compose = createComposeRule()

    @Test fun formatsDecimalUnitsWithLocaleDigitsAndBoundary() {
        assertEquals("999.0 MB", FileSizeFormatter.format(999_000_000L, Locale.US))
        assertEquals("1.0 GB", FileSizeFormatter.format(1_000_000_000L, Locale.US))
        assertEquals("850.0 MB", FileSizeFormatter.format(850_000_000L, Locale.US))
        assertEquals("١٫٤ GB", FileSizeFormatter.format(1_400_000_000L, Locale("ar")))
    }

    @Test fun displayPrefersExactThenEstimateAndHidesUnknown() {
        val base = MediaFileSizeEntity("a", "movie", 0, "7")
        val exact = FileSizeFormatter.display(base.copy(exactBytes = 1_400_000_000L, estimatedBytes = 900_000_000L))
        assertEquals("1.4 GB", exact?.text)
        assertFalse(exact!!.isEstimate)
        val estimate = FileSizeFormatter.display(base.copy(estimatedBytes = 850_000_000L))
        assertEquals("850.0 MB", estimate?.text)
        assertTrue(estimate!!.isEstimate)
        assertNull(FileSizeFormatter.display(base))
    }

    @Test fun composeRendersExactAndEstimatedLabelsAndOmitsUnknown() {
        compose.setContent {
            Column {
                FileSizeLabel(FileSizeDisplay("1.4 GB", false))
                FileSizeLabel(FileSizeDisplay("850.0 MB", true))
                FileSizeLabel(null)
            }
        }
        compose.onNodeWithText("File size 1.4 GB").assertIsDisplayed()
        compose.onNodeWithText("File size ~850.0 MB").assertIsDisplayed()
        compose.onNodeWithContentDescription("Approximate file size: 850.0 MB").assertIsDisplayed()
        compose.onAllNodesWithText("File size", substring = true).assertCountEquals(2)
    }
}
