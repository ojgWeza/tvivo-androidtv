package com.dev.Tvivo.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AdaptiveSpacingTest {
    @Test fun phoneTabletAndTelevisionKeepDistinctSpacingModels() {
        val phone = AdaptiveSpacing.forEnvironment(false, WindowSizeClass.Compact)
        val tablet = AdaptiveSpacing.forEnvironment(false, WindowSizeClass.Expanded)
        val televisionAtPhoneWidth = AdaptiveSpacing.forEnvironment(true, WindowSizeClass.Compact)
        val televisionAtTabletWidth = AdaptiveSpacing.forEnvironment(true, WindowSizeClass.Expanded)

        assertNotEquals(phone.outerGutter, televisionAtPhoneWidth.outerGutter)
        assertNotEquals(tablet.cardGap, televisionAtTabletWidth.cardGap)
        assertEquals(televisionAtPhoneWidth, televisionAtTabletWidth)
        assertNotEquals(phone, tablet)
    }
}
