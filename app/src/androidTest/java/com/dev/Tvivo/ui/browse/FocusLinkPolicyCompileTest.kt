package com.dev.Tvivo.ui.browse

import androidx.compose.ui.focus.FocusRequester
import com.dev.Tvivo.home.FocusLinkPolicy
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compile coverage for the Compose requester-to-default fallback used by the D-pad links. */
class FocusLinkPolicyCompileTest {
    @Test fun absentGridUsesComposeDefaultSearchTarget() {
        val grid = FocusRequester()
        val target = FocusLinkPolicy.targetIfRendered(grid, isRendered = false)
            ?: FocusRequester.Default
        assertSame(FocusRequester.Default, target)
    }

    @Test fun openedGridSortLabelsArePresentAndNonEmpty() {
        val labels = catalogSortLabels()
        assertEquals(listOf("A–Z", "Z–A", "Recently added"), labels)
        assertTrue(labels.all { it.isNotBlank() })
    }
}
