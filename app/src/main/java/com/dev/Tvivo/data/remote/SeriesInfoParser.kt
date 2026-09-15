package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.EpisodeEntity
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * `get_series_info` → [EpisodeEntity].
 *
 * The shape that makes this its own parser: `episodes` is **an object keyed by season
 * number as a string**, not an array. Deserialising it into a list silently yields
 * nothing, and the failure looks like "this show has no episodes" rather than a parse
 * error, so it is tested against a captured response.
 *
 * Read whole rather than streamed, unlike the catalog lists: this is one show, fetched
 * only when the user opens it.
 *
 * Everything here is defensive by design. Observed on this panel:
 * - `id` (the episode id) is a quoted string, and it is what goes in the playback URL.
 * - `episode_num` is sometimes a string, sometimes an int.
 * - The `episodes` key and the body's own `season` field occasionally disagree; the key
 *   wins, because it is what groups the picker.
 * - Season keys are not always contiguous, and `"0"` (specials) is a real season.
 * - `container_extension` may be missing; the caller falls back to `mkv`.
 * - `duration_secs` is **not** reliable on this panel. Observed on a 60-episode drama:
 *   every episode reported 9-190 "seconds" while the picker should have read tens of
 *   minutes. `duration` is the human-authored `HH:MM:SS` field and it is what a viewer
 *   can check against the player, so it wins; `duration_secs` is only the fallback.
 * - Episode titles repeat the show's leading quality token (`"HD  <show> - S01E01"`), so
 *   they go through the same [NameNormalizer] display pass the grid uses. Without it the
 *   picker reads `HD` on every row while the show above it does not, and the leading LTR
 *   run breaks truncation on Arabic titles for the same reason it does in the grid.
 */
object SeriesInfoParser {

    fun parse(json: String, accountId: String, seriesId: Int): List<EpisodeEntity> {
        if (json.isBlank()) return emptyList()
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        if (!root.isJsonObject) return emptyList()

        val episodes = root.asJsonObject.get("episodes")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return emptyList()

        val rows = ArrayList<EpisodeEntity>()
        for ((seasonKey, value) in episodes.entrySet()) {
            // A panel that has nothing for a season sometimes sends an empty object
            // instead of an empty array.
            val array = value.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            val seasonFromKey = seasonKey.trim().toIntOrNull()

            array.forEachIndexed { index, element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
                val episodeId = obj.string("id") ?: return@forEachIndexed
                rows.add(
                    EpisodeEntity(
                        accountId = accountId,
                        seriesId = seriesId,
                        episodeId = episodeId,
                        seasonNumber = seasonFromKey ?: obj.string("season")?.toIntOrNull() ?: 0,
                        // Position in the season is the fallback: an episode with no
                        // number must still sort somewhere stable rather than collapsing
                        // onto every other unnumbered episode at 0.
                        episodeNum = obj.string("episode_num")?.toIntOrNull() ?: (index + 1),
                        title = obj.string("title")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { NameNormalizer.of(it).display }
                            ?: defaultTitle(seasonFromKey, index),
                        containerExtension = obj.string("container_extension")
                            ?.takeIf { it.isNotBlank() },
                        durationSecs = durationOf(obj),
                        added = obj.string("added")?.toLongOrNull()
                    )
                )
            }
        }
        return rows
    }

    /**
     * Runtime in seconds, from the most trustworthy field present. `duration` first
     * because this panel's `duration_secs` disagrees with it — see the class note.
     */
    private fun durationOf(obj: JsonObject): Int? =
        parseClock(obj.string("duration"))
            ?: parseClock(obj.info()?.string("duration"))
            ?: obj.string("duration_secs")?.toIntOrNull()
            ?: obj.info()?.string("duration_secs")?.toIntOrNull()

    /** `HH:MM:SS` or `MM:SS`; anything else is not a clock and is left to the fallback. */
    private fun parseClock(raw: String?): Int? {
        val parts = raw?.trim()?.split(":")?.takeIf { it.size in 2..3 } ?: return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return numbers.fold(0) { acc, part -> acc * 60 + part }.takeIf { it > 0 }
    }

    /** Season metadata, used only to label the picker when a season has a real name. */
    fun parseSeasonNames(json: String): Map<Int, String> {
        if (json.isBlank()) return emptyMap()
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyMap()
        val seasons = root.takeIf { it.isJsonObject }?.asJsonObject?.get("seasons")
            ?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyMap()

        return seasons.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val number = obj.string("season_number")?.toIntOrNull() ?: return@mapNotNull null
            val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            number to name
        }.toMap()
    }

    private fun defaultTitle(season: Int?, index: Int): String =
        "S%02dE%02d".format(season ?: 0, index + 1)

    /** The per-episode `info` block is transcoding metadata; only the runtime is useful. */
    private fun JsonObject.info(): JsonObject? =
        get("info")?.takeIf { it.isJsonObject }?.asJsonObject

    /** Numbers, strings and quoted numbers all arrive here; nulls and containers do not. */
    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isNotNullPrimitive() }?.asString

    private fun JsonElement.isNotNullPrimitive(): Boolean = !isJsonNull && isJsonPrimitive
}
