package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.local.NameNormalizer

/** Keeps provider episode names as the playback label; ordinals remain separate UI metadata. */
object EpisodeTitlePolicy {
    private val genericEpisode = Regex("(?i)^(?:episode\\s*\\d+|الحلقة\\s*\\d+)$")
    private val episodePattern = Regex("(?i)\\bS\\d{1,3}\\s*E\\d{1,3}\\b")
    private val prefixSeparator = Regex("^\\s*[-:|–—]+\\s*")

    fun displayTitle(rawTitle: String?, ordinal: Int, showName: String? = null): String {
        var source = rawTitle?.takeIf(String::isNotBlank)?.let(NameNormalizer::of)?.display.orEmpty()
        val show = showName?.trim()?.takeIf(String::isNotBlank)
        if (show != null && source.startsWith(show, ignoreCase = true)) {
            val suffix = source.substring(show.length)
            if (prefixSeparator.containsMatchIn(suffix)) source = prefixSeparator.replaceFirst(suffix, "")
        }
        val cleaned = source.takeIf(String::isNotBlank)?.let(NameNormalizer::of)?.display.orEmpty()
        return if (cleaned.isBlank() || genericEpisode.matches(cleaned)) "Episode $ordinal" else cleaned
    }

    fun showOrdinal(title: String): Boolean = episodePattern.containsMatchIn(title) &&
        !genericEpisode.matches(title)

    fun isGenericTitle(title: String): Boolean = genericEpisode.matches(title.trim())
}
