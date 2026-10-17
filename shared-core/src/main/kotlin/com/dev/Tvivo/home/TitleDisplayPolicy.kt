package com.dev.Tvivo.home

import java.util.Locale

/** Pure title-edge cleanup shared by the Android display mapper and JVM golden tests. */
object TitleDisplayPolicy {
    data class Result(val display: String, val quality: String?)

    private val tokens = setOf(
        "hd", "fhd", "sd", "uhd", "4k", "1080p", "720p", "480p", "2160p", "hevc", "h265"
    )
    private val whitespace = Regex("\\s+")
    private val rank = listOf(
        "4K", "2160P", "UHD", "FHD", "1080P", "HD", "720P", "HEVC", "H265", "SD", "480P"
    )

    fun clean(value: String): Result {
        var title = value.trim().replace(whitespace, " ")
        val found = mutableListOf<String>()
        var changed: Boolean
        do {
            changed = false
            val head = title.substringBefore(' ', title)
            if (isQuality(head) && head.length < title.length) {
                found += canonical(head)
                title = title.removePrefix(head).trim()
                changed = true
            }
            val tail = title.substringAfterLast(' ', title)
            if (isQuality(tail) && tail.length < title.length) {
                found += canonical(tail)
                title = title.removeSuffix(tail).trim()
                changed = true
            }
        } while (changed)

        val quality = found.minByOrNull { rank.indexOf(it).let { index ->
            if (index < 0) Int.MAX_VALUE else index
        } }
        return Result(title.ifBlank { quality ?: "Untitled" }, quality)
    }

    private fun canonical(token: String) = token.trim().trim('[', ']', '(', ')', '-', '|', ':')
        .uppercase(Locale.ROOT)

    private fun isQuality(token: String): Boolean {
        val cleaned = token.trim().trim('[', ']', '(', ')', '-', '|', ':').lowercase(Locale.ROOT)
        return cleaned.isNotEmpty() && cleaned in tokens
    }
}
