package com.dev.Tvivo.data.remote

import org.json.JSONObject
import okhttp3.ResponseBody

/**
 * `get_vod_info` → the one field the list call does not give us.
 *
 * **Why this endpoint exists at all in this app.** Verified 2026-09-10 against the real
 * panel: a forced re-sync of all three catalogs produced **0 plots across 48,780
 * `get_vod_streams` rows**, while `get_series` returned 7,604 through the identical
 * parser. Movies simply do not carry a description in the list response, so a per-film
 * call is the only route to one.
 *
 * **Materialised rather than stream-parsed**, unlike every other parser here: this is one
 * object for one film, not a 15 MB catalog. The heap rule that governs [StreamListParser]
 * does not apply and a `JsonReader` would only make it harder to read.
 *
 * **Defensive about shape on purpose.** This endpoint was undocumented for this panel
 * when it was written, and Xtream forks disagree on where the description lives — some
 * nest it under `info`, some put it at the top level, some call it `description`. Every
 * variant is checked and anything unrecognised yields null, which the detail screen
 * already renders as "no description" rather than as an error.
 */
object VodInfoParser {

    data class Details(
        val plot: String?, val genre: String?, val cast: String?,
        val year: String?, val durationMinutes: Int?, val trailer: String?
    )

    fun parseDetails(body: ResponseBody): Details? = runCatching {
        val root = JSONObject(body.string())
        val info = root.optJSONObject("info")
        fun field(vararg keys: String): String? =
            firstNonBlank(info, keys.toList()) ?: firstNonBlank(root, keys.toList())
        val duration = field("duration")?.split(":")?.mapNotNull { it.toIntOrNull() }
        val minutes = when (duration?.size) {
            3 -> duration[0] * 60 + duration[1]
            2 -> duration[0]
            else -> null
        }
        Details(field(*PLOT_KEYS.toTypedArray()), field("genre"), field("cast"),
            field("releasedate", "releaseDate", "year")?.take(4), minutes,
            field("youtube_trailer", "trailer"))
    }.getOrNull()

    /** Just the plot. Nothing else on the pre-run page comes from this call. */
    fun parsePlot(body: ResponseBody): String? = parseDetails(body)?.plot

    private fun firstNonBlank(obj: JSONObject?, keys: List<String>): String? {
        if (obj == null) return null
        for (key in keys) {
            val value = obj.optString(key, "").trim()
            // `optString` turns a JSON null into the literal "null" — a real trap here,
            // because it is non-blank and would render as the description.
            if (value.isNotEmpty() && !value.equals("null", ignoreCase = true)) return value
        }
        return null
    }

    private val PLOT_KEYS = listOf("plot", "description", "overview", "storyline")
}
