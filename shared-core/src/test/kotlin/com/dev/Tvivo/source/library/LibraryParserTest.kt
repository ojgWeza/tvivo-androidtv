package com.dev.Tvivo.source.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryParserTest {
    @Test fun parsesGoldenMovieSeriesAndMusicIndexes() {
        assertEquals(1, valid(parseMovies(fixture("valid-movies.json"))).items.items.size)
        val series = valid(parseSeries(fixture("valid-series.json"))).items.items.single()
        assertEquals(1, series.seasons.single().episodes.size)
        assertEquals(1, valid(parseMusic(fixture("valid-music.json"))).items.items.size)
    }

    @Test fun acceptsAValidEmptyList() {
        assertTrue(valid(parseMovies(fixture("empty-list.json"))).items.items.isEmpty())
    }

    @Test fun parsesOnlyACompleteV1Marker() {
        assertEquals("complete", valid(parseLibraryMarker("""{"format":"tvivo.link-library","version":1,"bootstrap":"complete"}""")).items.bootstrap)
        rejected(parseLibraryMarker("{}"), RejectionReason.MISSING_REQUIRED_FIELD)
    }

    @Test fun rejectsCorruptWrongKindDuplicateOversizedAndUnknownVersionFixtures() {
        rejected(parseMovies(fixture("corrupt-json.json")), RejectionReason.MALFORMED_JSON)
        rejected(parseMovies(fixture("wrong-kind.json")), RejectionReason.WRONG_KIND)
        rejected(parseMovies(fixture("duplicate-ids.json")), RejectionReason.DUPLICATE_ID)
        rejected(parseMovies(fixture("oversized.json")), RejectionReason.TOO_LARGE)
        rejected(parseMovies(fixture("version-2.json")), RejectionReason.UNSUPPORTED_VERSION)
    }

    @Test fun rejectsUnsafeSchemesPrivateHostsAndOversizedUrls() {
        for (url in listOf("file:///tmp/movie.mp4", "content://media/movie", "javascript:alert(1)", "https://localhost/movie", "https://192.168.1.2/movie")) {
            rejected(parseMovies(movieWithUrl(url)), RejectionReason.UNSAFE_URL)
        }
        rejected(parseMovies(movieWithUrl("https://example.org/${"a".repeat(2_050)}")), RejectionReason.INVALID_FIELD)
    }

    @Test fun permitsOnlyLocalHttpWhenMockFlagIsEnabled() {
        assertTrue(parseMovies(movieWithUrl("http://127.0.0.1:8080/movie.mp4"), allowMockHttp = true) is ParseResult.Valid)
        rejected(parseMovies(movieWithUrl("http://example.org/movie.mp4"), allowMockHttp = true), RejectionReason.UNSAFE_URL)
    }

    @Test fun rejectsMissingRequiredFieldsAndMoreThanOneThousandItems() {
        rejected(parseMovies("""{"format":"tvivo.link-library","version":1,"kind":"movies","items":[{}]}"""), RejectionReason.MISSING_REQUIRED_FIELD)
        val item = """{"id":"d44bfc8e-4106-4a8a-8b69-b57c1d03486a","title":"A","group":"G","streamUrl":"https://example.org/a"}"""
        val index = """{"format":"tvivo.link-library","version":1,"kind":"movies","items":[${List(1_001) { item }.joinToString(",") }]}"""
        rejected(parseMovies(index), RejectionReason.TOO_MANY_ITEMS)
    }

    @Test fun seriesIdsAreUniqueAcrossShowsAndEpisodesAndSeasonsArePositive() {
        val duplicate = fixture("valid-series.json").replace("aa18a638-a96c-4534-97a8-b60f2fdd56c2", "d44bfc8e-4106-4a8a-8b69-b57c1d03486a")
        rejected(parseSeries(duplicate), RejectionReason.DUPLICATE_ID)
        rejected(parseSeries(fixture("valid-series.json").replace("\"number\":1", "\"number\":0")), RejectionReason.INVALID_FIELD)
    }

    @Test fun seriesLimitCountsEpisodesAsMediaItems() {
        val episodes = (1..1_000).joinToString(",") { number ->
            """{"id":"00000000-0000-4000-8000-${number.toString().padStart(12, '0')}","number":$number,"title":"E$number","streamUrl":"https://example.org/$number.mp4"}"""
        }
        val index = """{"format":"tvivo.link-library","version":1,"kind":"series","items":[{"id":"d44bfc8e-4106-4a8a-8b69-b57c1d03486a","title":"Show","group":"G","seasons":[{"number":1,"episodes":[$episodes]}]}]}"""
        rejected(parseSeries(index), RejectionReason.TOO_MANY_ITEMS)
    }

    private fun movieWithUrl(url: String) = """{"format":"tvivo.link-library","version":1,"kind":"movies","items":[{"id":"d44bfc8e-4106-4a8a-8b69-b57c1d03486a","title":"Movie","group":"G","streamUrl":"$url"}]}"""
    private fun fixture(name: String) = javaClass.getResource("/library/$name")!!.readText()
    private fun <T> valid(result: ParseResult<T>): ParseResult.Valid<T> = result as ParseResult.Valid<T>
    private fun <T> rejected(result: ParseResult<T>, reason: RejectionReason) {
        assertTrue("Expected $reason, got $result", result is ParseResult.Rejected)
        assertEquals(reason, (result as ParseResult.Rejected).reason)
    }
}
