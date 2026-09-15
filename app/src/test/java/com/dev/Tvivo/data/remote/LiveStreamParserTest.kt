package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.local.entities.LiveStreamEntity
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live's one real difference from VOD is the field name carrying the playback
 * extension — `ext`, not `container_extension`. The rest of these cases exist because
 * the full-catalog call is **unverified** for `get_live_streams`
 * (`docs/xtream-api-reference.md`): a panel that rejects it answers with an object, and
 * that must read as zero rows rather than as an exception or a wiped catalog.
 */
class LiveStreamParserTest {

    private fun body(json: String) =
        json.toResponseBody("application/json".toMediaType())

    private suspend fun parseAll(json: String, chunkSize: Int = 500): List<LiveStreamEntity> {
        val collected = mutableListOf<LiveStreamEntity>()
        LiveStreamParser.parse(body(json), ACCOUNT, GENERATION, chunkSize) { chunk ->
            collected.addAll(chunk)
        }
        return collected
    }

    @Test
    fun `parses a real-shaped live channel`() = runTest {
        val json = """
            [{
              "added": "1782389941",
              "category_id": "823",
              "custom_sid": "",
              "direct_source": "",
              "epg_channel_id": "",
              "ext": "ts",
              "name": "تعليمات هامة",
              "num": 3,
              "stream_icon": "http://images.example.com/images/1721054693479.jpg",
              "stream_id": 357057,
              "stream_type": "live",
              "tv_archive": 0,
              "tv_archive_duration": 0
            }]
        """.trimIndent()

        val items = parseAll(json)
        assertEquals(1, items.size)
        val item = items.first()

        assertEquals(357057, item.streamId)
        assertEquals("823", item.categoryId)
        // The whole point of the shared reader: `ext` lands where VOD puts
        // `container_extension`.
        assertEquals("ts", item.ext)
        assertEquals(1782389941L, item.added)
        assertEquals(3, item.num)
        assertEquals(ACCOUNT, item.accountId)
        assertEquals(GENERATION, item.generation)
        assertEquals("تعليمات هامة", item.name)
        assertTrue(item.nameNormalized.isNotEmpty())
    }

    @Test
    fun `a panel that rejects the call answers with an object, which reads as zero rows`() =
        runTest {
            val items = parseAll("""{"user_info":{"auth":1}}""")
            assertTrue(items.isEmpty())
        }

    @Test
    fun `an empty array is zero rows, not an error`() = runTest {
        assertTrue(parseAll("[]").isEmpty())
    }

    @Test
    fun `a channel with no ext survives, and the URL builder supplies the default`() = runTest {
        val json = """[{ "stream_id": 1, "name": "Channel One", "category_id": "5" }]"""
        val item = parseAll(json).single()
        assertNull(item.ext)
        assertNull(item.added)
    }

    @Test
    fun `an item missing stream_id or name is dropped rather than stored half-formed`() =
        runTest {
            val json = """
                [
                  { "name": "No id", "ext": "ts" },
                  { "stream_id": 2, "ext": "ts" },
                  { "stream_id": 3, "name": "Kept", "ext": "ts" }
                ]
            """.trimIndent()
            val items = parseAll(json)
            assertEquals(listOf(3), items.map { it.streamId })
        }

    @Test
    fun `rows arrive in chunks and are never accumulated by the parser`() = runTest {
        val json = (1..7).joinToString(
            prefix = "[", postfix = "]", separator = ","
        ) { """{"stream_id":$it,"name":"Channel $it","ext":"ts"}""" }

        val chunkSizes = mutableListOf<Int>()
        val total = LiveStreamParser.parse(body(json), ACCOUNT, GENERATION, chunkSize = 3) { chunk ->
            chunkSizes.add(chunk.size)
        }

        assertEquals(7, total)
        assertEquals(listOf(3, 3, 1), chunkSizes)
    }

    private companion object {
        const val ACCOUNT = "acct-1"
        const val GENERATION = 42L
    }
}
