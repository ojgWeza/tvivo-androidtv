package com.dev.Tvivo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test fun exactStoredValuesRoundTrip() {
        ThemeMode.values().forEach { mode ->
            assertEquals(mode, ThemeMode.fromPreference(mode.toPreference()))
        }
    }

    @Test fun unknownOrCorruptValueFallsBackToDark() {
        listOf(null, "", "light", "System", "Dark ", "0", "not-json")
            .forEach { assertEquals(ThemeMode.Dark, ThemeMode.fromPreference(it)) }
    }
}
