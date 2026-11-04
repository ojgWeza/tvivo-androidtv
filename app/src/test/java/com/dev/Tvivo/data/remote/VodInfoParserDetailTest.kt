package com.dev.Tvivo.data.remote

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(VodInfoParser.Details("A story", "Drama", "Actor One", "2024", 101, "abc123", 6090),
            VodInfoParser.parseDetails(json.toResponseBody()))
    }

    @Test fun parses_size_aliases_as_bytes_and_decimal_units() {
        val cases = listOf(
            Triple("size", "1400000000", false),
            Triple("filesize", "1.4 GB", true),
            Triple("file_size", "1.4GB", true),
            Triple("size_bytes", "1400000000", true)
        )
        cases.forEach { (key, value, quoted) ->
            val encoded = if (quoted) "\"$value\"" else value
            val result = VodInfoParser.parseDetails("""{"info":{"$key":$encoded,"bitrate":2400,"duration":"00:10:00"}}""".toResponseBody())
            assertEquals(key, 1_400_000_000L, result?.sizeBytes)
            assertEquals(key, 2400, result?.bitrateKbps)
            assertEquals(key, 600, result?.durationSecs)
        }
    }

    @Test fun missing_or_invalid_size_and_bitrate_stay_null() {
        val result = VodInfoParser.parseDetails("""{"info":{"size":"unknown","bitrate":-1}}""".toResponseBody())
        assertNull(result?.sizeBytes)
        assertNull(result?.bitrateKbps)
    }
}
