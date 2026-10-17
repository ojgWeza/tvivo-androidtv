package com.dev.Tvivo.fixture

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same shared fixture directory is packaged as androidTest assets. */
class FixtureAssetsTest {
    @Test fun emulatorTestsCanReadContractAndArtworkWithoutAServer() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assets.open("xtream-fixture/series_info.json").bufferedReader().use {
            assertTrue(it.readText().contains("episode-A1"))
        }
        assets.open("xtream-fixture/artwork/poster.svg").bufferedReader().use {
            assertTrue(it.readText().contains("<svg"))
        }
    }
}
