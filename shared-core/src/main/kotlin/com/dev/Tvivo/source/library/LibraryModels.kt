package com.dev.Tvivo.source.library

data class LibraryMarker(val format: String, val version: Int, val bootstrap: String)

data class MovieEntry(
    val id: String,
    val title: String,
    val group: String,
    val streamUrl: String,
    val posterUrl: String? = null,
    val year: Int? = null,
    val plot: String? = null,
)

data class ShowEntry(
    val id: String,
    val title: String,
    val group: String,
    val seasons: List<SeasonEntry>,
    val year: Int? = null,
    val posterUrl: String? = null,
    val plot: String? = null,
)

data class SeasonEntry(val number: Int, val title: String?, val episodes: List<EpisodeEntry>)

data class EpisodeEntry(
    val id: String,
    val number: Int,
    val title: String,
    val streamUrl: String,
    val posterUrl: String? = null,
    val plot: String? = null,
)

data class SongEntry(
    val id: String,
    val title: String,
    val group: String,
    val streamUrl: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val year: Int? = null,
    val durationSeconds: Int? = null,
)

data class ParsedList<T>(val kind: String, val items: List<T>)

enum class RejectionReason {
    MALFORMED_JSON, TOO_LARGE, WRONG_FORMAT, UNSUPPORTED_VERSION, WRONG_KIND,
    MISSING_REQUIRED_FIELD, INVALID_FIELD, DUPLICATE_ID, TOO_MANY_ITEMS, UNSAFE_URL,
}

sealed interface ParseResult<out T> {
    data class Valid<T>(val items: T) : ParseResult<T>
    data class Rejected(val reason: RejectionReason, val message: String) : ParseResult<Nothing>
}
