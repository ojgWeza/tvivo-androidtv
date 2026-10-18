package com.dev.Tvivo.ui.adaptive

import org.junit.Assert.assertTrue
import org.junit.Test

class TelevisionDensityPolicyTest {
    @Test fun adaptsAcrossSmallAndLargeWindowsAndFontScale() {
        val small = TelevisionDensityPolicy.calculate(960, 540)
        val hd = TelevisionDensityPolicy.calculate(1280, 720)
        val fullHd = TelevisionDensityPolicy.calculate(1920, 1080)
        val fourK = TelevisionDensityPolicy.calculate(3840, 2160)
        val largeFont = TelevisionDensityPolicy.calculate(1920, 1080, 1.3f)

        assertTrue(small.posterWidth in 96..144)
        assertTrue(hd.posterWidth >= small.posterWidth)
        assertTrue(fullHd.posterWidth >= hd.posterWidth)
        assertTrue(fourK.posterWidth > fullHd.posterWidth)
        assertTrue(largeFont.posterWidth <= fullHd.posterWidth)
        assertTrue(largeFont.sideGutter in 24..40)
        assertTrue(fourK.shellHeight in 64..88)
        assertTrue(small.channelHeight < small.posterHeight)
    }
}
