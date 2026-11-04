package com.dev.Tvivo.ui.detail

import com.dev.Tvivo.data.local.entities.MediaFileSizeEntity
import java.text.NumberFormat
import java.util.Locale

data class FileSizeDisplay(val text: String, val isEstimate: Boolean)

/** Formats decimal file sizes. Units deliberately use decimal bytes (1 GB = 1,000,000,000). */
object FileSizeFormatter {
    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String? {
        if (bytes <= 0L) return null
        val divisor = if (bytes >= 1_000_000_000L) 1_000_000_000.0 else 1_000_000.0
        val unit = if (bytes >= 1_000_000_000L) "GB" else "MB"
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(bytes / divisor)
        return "$number $unit"
    }

    fun display(row: MediaFileSizeEntity?, locale: Locale = Locale.getDefault()): FileSizeDisplay? {
        row ?: return null
        row.exactBytes?.let { format(it, locale)?.let { text -> return FileSizeDisplay(text, false) } }
        row.estimatedBytes?.let { format(it, locale)?.let { text -> return FileSizeDisplay(text, true) } }
        return null
    }
}
