package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.local.entities.SeriesEntity
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `get_series` breaks the naming pattern on three fields — `series_id` not `stream_id`,
 * `cover` not `stream_icon`, `last_modified` not `added` — and its `category_id` is an
 * **int** where VOD and live send a string. Each of those silently drops rows or breaks
 * the grouped category counts if the shared reader gets it wrong.
 *
 * As with live, the full-catalog call is unverified for `get_series`
 * (`docs/xtream-api-reference.md`), so a rejecting panel's object answer must read as
 * zero rows rather than as an exception.
 */
class SeriesListParserTest {

    private fun body(json: String) = json.toResponseBody("application/json".toMediaType())

    private suspend fun parseAll(json: String, chunkSize: Int = 500): List<SeriesEntity> {
        val collected = mutableListOf<SeriesEntity>()
        SeriesListParser.parse(body(json), ACCOUNT, GENERATION, chunkSize) { chunk ->
            collected.addAll(chunk)
        }
        return collected
    }

    @Test
    fun `parses a real-shaped show`() = runTest {
        val json = """
            [{
              "backdrop_path": [],
              "cast": "",
              "category_id": 770,
              "cover": "http://images.example.com/images/1781463543334.jpg",
              "director": "",
              "episode_run_time": "0",
              "genre": "",
              "last_modified": "1788601549",
              "name": "HD  A Show",
              "num": 1,
              "plot": "A plot.",
              "rating": 0,
              "rating_5based": 0,
              "releaseDate": "",
              "series_id": 10840,
              "youtube_trailer": ""
            }]
        """.trimIndent()

        val row = parseAll(json).single()

        assertEquals(10840, row.seriesId)
        // The int must arrive as the same string the category rows are keyed by, or the
        // grouped counts miss every series row.
        assertEquals("770", row.categoryId)
        assertEquals("http://images.example.com/images/1781463543334.jpg", row.streamIcon)
        assertEquals(1788601549L, row.added)
        assertEquals("A plot.", row.plot)
        assertEquals(GENERATION, row.generation)
    }

    @Test
    fun `the quality token is stripped from the display name`() = runTest {
        val json = """[{"series_id":1,"name":"HD  A Show","cover":""}]"""
        val row = parseAll(json).single()

        assertEquals("HD  A Show", row.name)
        assertTrue(row.nameDisplay.isNotBlank())
        assertEquals(row.nameDisplay.lowercase(), row.nameNormalized.lowercase())
    }

    @Test
    fun `a blank plot becomes null rather than an empty line on screen`() = runTest {
        val row = parseAll("""[{"series_id":1,"name":"A Show","plot":""}]""").single()
        assertNull(row.plot)
    }

    @Test
    fun `a backdrop array and a null cover do not derail the reader`() = runTest {
        val json = """
            [{"series_id":1,"name":"A Show","cover":null,"backdrop_path":["a","b"]},
             {"series_id":2,"name":"Another Show"}]
        """.trimIndent()

        val rows = parseAll(json)

        assertEquals(listOf(1, 2), rows.map { it.seriesId })
        assertNull(rows[0].streamIcon)
    }

    @Test
    fun `a show with no series_id is dropped rather than defaulted to zero`() = runTest {
        val json = """[{"name":"No id"},{"series_id":2,"name":"Has id"}]"""
        assertEquals(listOf(2), parseAll(json).map { it.seriesId })
    }

    @Test
    fun `an object answer reads as zero rows, not an exception`() = runTest {
        // What a panel that rejects the no-category_id call actually returns. The
        // full-catalog sync treats zero rows as "keep what is cached".
        assertEquals(emptyList<SeriesEntity>(), parseAll("""{"user_info":{"auth":0}}"""))
    }

    @Test
    fun `an empty array reads as zero rows`() = runTest {
        assertEquals(emptyList<SeriesEntity>(), parseAll("[]"))
    }

    @Test
    fun `chunks are emitted at the chunk size and never accumulated`() = runTest {
        val json = (1..250).joinToString(",", "[", "]") {
            """{"series_id":$it,"name":"Show $it"}"""
        }
        val sizes = mutableListOf<Int>()
        val total = SeriesListParser.parse(body(json), ACCOUNT, GENERATION, chunkSize = 100) {
            sizes.add(it.size)
        }

        assertEquals(250, total)
        assertEquals(listOf(100, 100, 50), sizes)
    }

    private companion object {
        const val ACCOUNT = "acct-a"
        const val GENERATION = 1_700_000_000_000L
    }
}
