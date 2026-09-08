package com.dev.Tvivo.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `get_series_info` returns `episodes` as **an object keyed by season number as a
 * string**, not an array. Deserialising it as a list yields nothing and the failure
 * presents as "this show has no episodes" rather than as a parse error — so it is
 * pinned here against the captured response shape in
 * `docs/xtream-api-reference.md`.
 */
class SeriesInfoParserTest {

    private val realShaped = """
        {
          "seasons": [
            { "id": 1, "name": "Season 1", "episode_count": 2, "season_number": 1, "cover": "x" }
          ],
          "info": { "name": "A Show", "cover": "c", "plot": "p", "category_id": "770" },
          "episodes": {
            "1": [
              {
                "id": "533032",
                "episode_num": 1,
                "title": "A Show - S01E01",
                "container_extension": "mkv",
                "season": 1,
                "duration": "00:40:37",
                "info": { "duration_secs": 2437, "bitrate": 1200 },
                "added": "1781562565"
              },
              {
                "id": "533033",
                "episode_num": 2,
                "title": "A Show - S01E02",
                "container_extension": "mp4",
                "season": 1,
                "duration_secs": 2500,
                "added": "1781562566"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `parses the season-keyed object into episodes`() {
        val rows = SeriesInfoParser.parse(realShaped, ACCOUNT, SERIES_ID)

        assertEquals(2, rows.size)
        val first = rows.first()
        // The id is a quoted string and it is what goes in the playback URL — never
        // round-tripped through Int.
        assertEquals("533032", first.episodeId)
        assertEquals(1, first.seasonNumber)
        assertEquals(1, first.episodeNum)
        assertEquals("mkv", first.containerExtension)
        assertEquals(1781562565L, first.added)
        assertEquals(SERIES_ID, first.seriesId)
        assertEquals(ACCOUNT, first.accountId)
    }

    @Test
    fun `runtime is read from the episode or from its nested info block`() {
        val rows = SeriesInfoParser.parse(realShaped, ACCOUNT, SERIES_ID)

        assertEquals(2437, rows[0].durationSecs)
        assertEquals(2500, rows[1].durationSecs)
    }

    @Test
    fun `season names come from the seasons array`() {
        assertEquals(mapOf(1 to "Season 1"), SeriesInfoParser.parseSeasonNames(realShaped))
    }

    @Test
    fun `the episodes key wins when it disagrees with the episode body`() {
        // Observed on this panel: a handful of shows carry a `season` that contradicts
        // the key they are filed under. The key is what groups the picker, so it wins.
        val json = """
            {"episodes":{"2":[{"id":"1","episode_num":1,"title":"t","season":9}]}}
        """.trimIndent()

        assertEquals(2, SeriesInfoParser.parse(json, ACCOUNT, SERIES_ID).single().seasonNumber)
    }

    @Test
    fun `season zero is kept as a real season`() {
        val json = """{"episodes":{"0":[{"id":"1","episode_num":1,"title":"Special"}]}}"""

        assertEquals(0, SeriesInfoParser.parse(json, ACCOUNT, SERIES_ID).single().seasonNumber)
    }

    @Test
    fun `non-contiguous seasons all survive`() {
        val json = """
            {"episodes":{
              "1":[{"id":"1","episode_num":1,"title":"a"}],
              "4":[{"id":"2","episode_num":1,"title":"b"}]
            }}
        """.trimIndent()

        assertEquals(
            listOf(1, 4),
            SeriesInfoParser.parse(json, ACCOUNT, SERIES_ID).map { it.seasonNumber }.sorted()
        )
    }

    @Test
    fun `a quoted episode_num parses the same as an int`() {
        val json = """{"episodes":{"1":[{"id":"1","episode_num":"7","title":"t"}]}}"""

        assertEquals(7, SeriesInfoParser.parse(json, ACCOUNT, SERIES_ID).single().episodeNum)
    }

    @Test
    fun `an unnumbered episode falls back to its position, not to zero`() {
        // Otherwise every unnumbered episode in a season collapses onto 0 and the picker
        // shows them stacked in an arbitrary order.
        val json = """
            {"episodes":{"1":[{"id":"1","title":"a"},{"id":"2","title":"b"}]}}
        """.trimIndent()

        assertEquals(listOf(1, 2), SeriesInfoParser.parse(json, ACCOUNT, SERIES_ID).map { it.episodeNum })
    }

    @Test
    fun `a missing container_extension is left null for the caller to default`() {
        val json = """{"episodes":{"1":[{"id":"1","episode_num":1,"title":"t"}]}}"""

        assertNull(SeriesInfoParser.parse(json, ACCOUNT, SERIES_ID).single().containerExtension)
    }

    @Test
    fun `an episode with no id is dropped rather than built into a broken URL`() {
        val json = """
            {"episodes":{"1":[{"episode_num":1,"title":"no id"},{"id":"2","episode_num":2,"title":"ok"}]}}
        """.trimIndent()

        assertEquals(listOf("2"), SeriesInfoParser.parse(json, ACCOUNT, SERIES_ID).map { it.episodeId })
    }

    @Test
    fun `a titleless episode still gets something readable`() {
        val json = """{"episodes":{"3":[{"id":"1","episode_num":4}]}}"""

        assertEquals("S03E01", SeriesInfoParser.parse(json, ACCOUNT, SERIES_ID).single().title)
    }

    @Test
    fun `an error object, an episodes array and blank input all read as no episodes`() {
        // A rejecting panel answers with an error object; some answer with `episodes` as
        // an empty array. Neither may throw — and the DAO refuses to wipe a cached season
        // on an empty result.
        assertTrue(SeriesInfoParser.parse("""{"user_info":{"auth":0}}""", ACCOUNT, SERIES_ID).isEmpty())
        assertTrue(SeriesInfoParser.parse("""{"episodes":[]}""", ACCOUNT, SERIES_ID).isEmpty())
        assertTrue(SeriesInfoParser.parse("", ACCOUNT, SERIES_ID).isEmpty())
        assertTrue(SeriesInfoParser.parse("not json at all", ACCOUNT, SERIES_ID).isEmpty())
    }

    @Test
    fun `a season whose value is an object rather than an array is skipped, not fatal`() {
        val json = """
            {"episodes":{"1":{},"2":[{"id":"5","episode_num":1,"title":"t"}]}}
        """.trimIndent()

        assertEquals(listOf("5"), SeriesInfoParser.parse(json, ACCOUNT, SERIES_ID).map { it.episodeId })
    }

    private companion object {
        const val ACCOUNT = "acct-a"
        const val SERIES_ID = 10840
    }
}
