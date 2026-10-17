package com.dev.Tvivo.data.remote

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VodInfoParserDetailTest {
    @Test fun existingVodInfoResponseCarriesOptionalHeroFields() {
        val json = """{"info":{"plot":"A story","genre":"Drama","cast":"Actor One",
            "releasedate":"2024-01-02","duration":"01:41:30","youtube_trailer":"abc123"}}"""
        assertEquals(VodInfoParser.Details("A story", "Drama", "Actor One", "2024", 101, "abc123"),
            VodInfoParser.parseDetails(json.toResponseBody()))
    }
}
