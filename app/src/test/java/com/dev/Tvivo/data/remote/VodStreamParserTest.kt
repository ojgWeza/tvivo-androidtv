package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.local.entities.VodStreamEntity
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercised against the exact field shapes in `docs/xtream-api-reference.md`, including
 * the ones that are easy to assume away: `custom_sid` null, `added` as a string, an
 * absent `container_extension`.
 */
class VodStreamParserTest {

    private fun body(json: String) =
        json.toResponseBody("application/json".toMediaType())

    private suspend fun parseAll(json: String, chunkSize: Int = 500): List<VodStreamEntity> {
        val collected = mutableListOf<VodStreamEntity>()
        VodStreamParser.parse(body(json), ACCOUNT, GENERATION, chunkSize) { chunk ->
            collected.addAll(chunk)
        }
        return collected
    }

    @Test
    fun `parses a real-shaped vod item`() = runTest {
        val json = """
            [{
              "added": "1768511625",
              "category_id": "898",
              "container_extension": "mkv",
              "custom_sid": null,
              "direct_source": "",
              "name": "وقت إضافي (2026)",
              "num": 33,
              "rating": 0,
              "rating_5based": "0",
              "stream_icon": "http://images.example.com/images/1768511665129.jpg",
              "stream_id": 505439,
              "stream_type": "movie"
            }]
        """.trimIndent()

        val items = parseAll(json)
        assertEquals(1, items.size)
        val item = items.first()

        assertEquals(505439, item.streamId)
        assertEquals("898", item.categoryId)
        assertEquals("mkv", item.containerExtension)
        assertEquals(1768511625L, item.added)
        assertEquals(33, item.num)
        assertEquals(ACCOUNT, item.accountId)
        assertEquals(GENERATION, item.generation)
        assertEquals("وقت إضافي (2026)", item.name)
    }

    @Test
    fun `all three name columns are populated`() = runTest {
        val json = """[{"stream_id":1,"name":"HD Some Movie","category_id":"5"}]"""
        val item = parseAll(json).single()

        assertEquals("HD Some Movie", item.name)
        assertEquals("Some Movie", item.nameDisplay)
        assertEquals("some movie", item.nameNormalized)
    }

    @Test
    fun `numeric category_id is accepted, not just string`() = runTest {
        // category_id is a string on VOD and live but an int on series objects.
        val json = """[{"stream_id":1,"name":"X","category_id":770}]"""
        assertEquals("770", parseAll(json).single().categoryId)
    }

    @Test
    fun `missing optional fields do not drop the row`() = runTest {
        val json = """[{"stream_id":7,"name":"Bare"}]"""
        val item = parseAll(json).single()

        assertEquals(7, item.streamId)
        assertNull(item.categoryId)
        assertNull(item.containerExtension)
        assertNull(item.added)
    }

    @Test
    fun `rows without a stream_id or name are skipped rather than crashing`() = runTest {
        val json = """
            [
              {"stream_id":1,"name":"Keep"},
              {"name":"No id"},
              {"stream_id":3},
              {"stream_id":4,"name":"Keep too"}
            ]
        """.trimIndent()

        val items = parseAll(json)
        assertEquals(2, items.size)
        assertEquals(listOf(1, 4), items.map { it.streamId })
    }

    @Test
    fun `unknown fields are skipped, including nested objects and arrays`() = runTest {
        val json = """
            [{
              "stream_id": 1,
              "name": "X",
              "backdrop_path": ["a","b"],
              "info": {"codec":"hevc","nested":{"deep":1}},
              "unexpected": true
            }]
        """.trimIndent()

        assertEquals(1, parseAll(json).single().streamId)
    }

    @Test
    fun `an error object instead of an array yields nothing rather than throwing`() = runTest {
        // A panel that rejects the call answers with an object.
        assertEquals(0, parseAll("""{"user_info":{"auth":0}}""").size)
    }

    @Test
    fun `empty array yields nothing`() = runTest {
        assertEquals(0, parseAll("[]").size)
    }

    /** The whole point of the parser: rows arrive in chunks and are never accumulated. */
    @Test
    fun `chunks are emitted at the configured size`() = runTest {
        val json = (1..1250).joinToString(",", "[", "]") {
            """{"stream_id":$it,"name":"Movie $it"}"""
        }

        val chunkSizes = mutableListOf<Int>()
        val total = VodStreamParser.parse(body(json), ACCOUNT, GENERATION, chunkSize = 500) {
            chunkSizes.add(it.size)
        }

        assertEquals(1250, total)
        assertEquals(listOf(500, 500, 250), chunkSizes)
    }

    @Test
    fun `returned count matches the number of rows emitted`() = runTest {
        val json = (1..37).joinToString(",", "[", "]") {
            """{"stream_id":$it,"name":"M$it"}"""
        }

        var emitted = 0
        val total = VodStreamParser.parse(body(json), ACCOUNT, GENERATION, chunkSize = 10) {
            emitted += it.size
        }

        assertEquals(37, total)
        assertEquals(37, emitted)
    }

    @Test
    fun `stream_id given as a string is still read`() = runTest {
        val json = """[{"stream_id":"505439","name":"X"}]"""
        assertEquals(505439, parseAll(json).single().streamId)
    }

    @Test
    fun `arabic titles survive parsing intact`() = runTest {
        val json = """[{"stream_id":1,"name":"HD  للعدالة وجه آخر"}]"""
        val item = parseAll(json).single()

        assertTrue(item.nameDisplay.startsWith("للعدالة"))
        assertEquals("HD  للعدالة وجه آخر", item.name)
    }

    private companion object {
        const val ACCOUNT = "acct123"
        const val GENERATION = 42L
    }
}
