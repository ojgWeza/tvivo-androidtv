package com.dev.Tvivo.home

import java.text.Normalizer
import java.util.Locale

/** Name key shared by Room-backed catalog queries and in-memory virtual-folder sorting. */
object NormalizedNameKey {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val whitespace = Regex("\\s+")

    fun of(value: String): String {
        val lowered = value.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        return combiningMarks.replace(decomposed, "")
            .let { Normalizer.normalize(it, Normalizer.Form.NFC) }
            .let { whitespace.replace(it, " ") }
            .trim()
    }
}
