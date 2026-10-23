package com.dev.Tvivo.source.library

/** Placeholder seed links only. TODO-verify public-domain/sample rights and direct playback URLs before shipping. */
object Seed {
    private const val MOVIE = "https://media.example.invalid/movies"
    private const val MUSIC = "https://media.example.invalid/music"

    val movies = listOf(
        MovieEntry("00000000-0000-4000-8000-000000000001", "Sample Movie One", "Sample Movies", "$MOVIE/one.mp4"),
        MovieEntry("00000000-0000-4000-8000-000000000002", "Sample Movie Two", "Sample Movies", "$MOVIE/two.mp4"),
        MovieEntry("00000000-0000-4000-8000-000000000003", "Sample Movie Three", "Sample Movies", "$MOVIE/three.mp4"),
        MovieEntry("00000000-0000-4000-8000-000000000004", "Sample Movie Four", "Sample Movies", "$MOVIE/four.mp4"),
        MovieEntry("00000000-0000-4000-8000-000000000005", "Sample Movie Five", "Sample Movies", "$MOVIE/five.mp4"),
    )

    val series = listOf(
        ShowEntry("00000000-0000-4000-8000-000000000011", "Sample Series One", "Sample Series", listOf(
            SeasonEntry(1, "Season 1", listOf(
                EpisodeEntry("00000000-0000-4000-8000-000000000012", 1, "Episode One", "$MOVIE/series-one-episode-one.mp4"),
                EpisodeEntry("00000000-0000-4000-8000-000000000013", 2, "Episode Two", "$MOVIE/series-one-episode-two.mp4"),
            )),
        )),
        ShowEntry("00000000-0000-4000-8000-000000000014", "Sample Series Two", "Sample Series", listOf(
            SeasonEntry(1, "Season 1", listOf(
                EpisodeEntry("00000000-0000-4000-8000-000000000015", 1, "Episode One", "$MOVIE/series-two-episode-one.mp4"),
                EpisodeEntry("00000000-0000-4000-8000-000000000016", 2, "Episode Two", "$MOVIE/series-two-episode-two.mp4"),
            )),
        )),
    )

    val songs = listOf(
        SongEntry("00000000-0000-4000-8000-000000000021", "Sample Song One", "Sample Music", "$MUSIC/one.mp3"),
        SongEntry("00000000-0000-4000-8000-000000000022", "Sample Song Two", "Sample Music", "$MUSIC/two.mp3"),
        SongEntry("00000000-0000-4000-8000-000000000023", "Sample Song Three", "Sample Music", "$MUSIC/three.mp3"),
        SongEntry("00000000-0000-4000-8000-000000000024", "Sample Song Four", "Sample Music", "$MUSIC/four.mp3"),
        SongEntry("00000000-0000-4000-8000-000000000025", "Sample Song Five", "Sample Music", "$MUSIC/five.mp3"),
    )

    fun buildSeedContent() = SeedContent(movies = movies, series = series, songs = songs)
}

data class SeedContent(
    val movies: List<MovieEntry>,
    val series: List<ShowEntry>,
    val songs: List<SongEntry>,
)
