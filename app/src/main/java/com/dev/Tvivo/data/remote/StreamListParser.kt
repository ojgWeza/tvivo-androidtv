package com.dev.Tvivo.data.remote

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.ResponseBody
import java.io.InputStreamReader

/**
 * The streaming JSON reader behind `get_vod_streams`, `get_live_streams` and
 * `get_series`.
 *
 * The three responses are the same shape with fields renamed — VOD carries
 * `container_extension`, live carries `ext`; VOD and live carry `stream_id` and
 * `stream_icon`, series carries `series_id` and `cover` — so the differences are
 * absorbed here and no caller learns about them. [VodStreamParser], [LiveStreamParser]
 * and [SeriesListParser] are the typed wrappers; this holds the parsing rules and the
 * memory contract.
 *
 * A full-catalog response is ~15 MB of JSON; parsed into a `List<T>` that is 30–50 MB of
 * heap on a box whose per-app limit may be 96 MB, *while* a Paging grid and a Coil bitmap
 * cache are live. So nothing here ever holds the whole response — [parse] hands each chunk
 * to `onChunk` and forgets it.
 *
 * Uses Gson's streaming reader rather than `android.util.JsonReader` so the parsing
 * rules — which is where the panel's field quirks live — are testable off-device.
 *
 * Fields are read defensively: `category_id` is a string on VOD items but an int on series
 * objects, `custom_sid` can be null, and `rating` is frequently 0 or absent.
 */
object StreamListParser {

    /** One panel list item, before it becomes a VOD, live or series entity. */
    data class RawStream(
        /** `stream_id` on VOD/live, `series_id` on series. */
        val streamId: Int,
        val categoryId: String?,
        val name: String,
        val streamIcon: String?,
        /** `container_extension` on VOD, `ext` on live — whichever the item carried. */
        val extension: String?,
        val added: Long?,
        val num: Int?,
        /** Sent on VOD and series rows; absent on live, which has nothing to describe. */
        val plot: String? = null,
        /**
         * Normalised to a **0–10** scale, or null when the panel did not rate the item.
         *
         * The panel sends both `rating` (0–10) and `rating_5based` (0–5), either as a
         * number or as a quoted string, and the reference notes both are "frequently 0".
         * A `0` here means *unrated*, not "rated zero" — so it is mapped to null rather
         * than rendered, because a wall of `0.0` badges is worse than no badge.
         */
        val rating: Double? = null
    )

    suspend fun <T> parse(
        body: ResponseBody,
        chunkSize: Int = 500,
        map: (RawStream) -> T,
        onChunk: suspend (List<T>) -> Unit
    ): Int {
        var total = 0
        val buffer = ArrayList<T>(chunkSize)
        val reader = JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))

        try {
            reader.isLenient = true
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                // A panel that rejects the call answers with an object, not an array.
                reader.skipValue()
                return 0
            }
            reader.beginArray()
            while (reader.hasNext()) {
                readItem(reader)?.let { buffer.add(map(it)) }
                if (buffer.size >= chunkSize) {
                    total += buffer.size
                    onChunk(ArrayList(buffer))
                    buffer.clear()
                }
            }
            reader.endArray()

            if (buffer.isNotEmpty()) {
                total += buffer.size
                onChunk(ArrayList(buffer))
                buffer.clear()
            }
        } finally {
            reader.close()
            body.close()
        }
        return total
    }

    private fun readItem(reader: JsonReader): RawStream? {
        var streamId: Int? = null
        var categoryId: String? = null
        var name: String? = null
        var icon: String? = null
        var extension: String? = null
        var added: Long? = null
        var num: Int? = null
        var plot: String? = null
        var rating: Double? = null
        var rating5: Double? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                // Series breaks the naming pattern on three fields and matches on the
                // rest, so it is aliases here rather than a second reader.
                "stream_id", "series_id" -> streamId = reader.nextIntOrNull()
                "category_id" -> categoryId = reader.nextStringOrNull()
                "name" -> name = reader.nextStringOrNull()
                "stream_icon", "cover" -> icon = reader.nextStringOrNull()
                "container_extension", "ext" -> extension = reader.nextStringOrNull()
                "added", "last_modified" -> added = reader.nextStringOrNull()?.toLongOrNull()
                "num" -> num = reader.nextIntOrNull()
                "plot" -> plot = reader.nextStringOrNull()
                "rating" -> rating = reader.nextDoubleOrNull()
                "rating_5based" -> rating5 = reader.nextDoubleOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (streamId == null || name == null) return null

        return RawStream(
            streamId = streamId,
            categoryId = categoryId,
            name = name,
            streamIcon = icon,
            extension = extension,
            added = added,
            num = num,
            plot = plot,
            rating = normaliseRating(rating, rating5)
        )
    }

    /**
     * `rating` wins when it is populated; `rating_5based` is doubled onto the same 0–10
     * scale as the fallback. Anything at or below zero, or out of range, is treated as
     * *unrated* — panels send `0` for "no rating" far more often than they send a real
     * zero, and a few send a 5-based value in the `rating` field, which the upper bound
     * catches rather than rendering as `4.5/10` for a 4.5-star film.
     */
    private fun normaliseRating(rating: Double?, rating5: Double?): Double? {
        val fromTen = rating?.takeIf { it > 0.0 && it <= 10.0 }
        if (fromTen != null) return fromTen
        val fromFive = rating5?.takeIf { it > 0.0 && it <= 5.0 }
        return fromFive?.let { it * 2.0 }
    }

    private fun JsonReader.nextStringOrNull(): String? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.NUMBER, JsonToken.STRING -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); null }
    }

    private fun JsonReader.nextIntOrNull(): Int? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.NUMBER -> nextInt()
        JsonToken.STRING -> nextString().toIntOrNull()
        else -> { skipValue(); null }
    }

    /** `rating` arrives as a bare number on some rows and a quoted string on others. */
    private fun JsonReader.nextDoubleOrNull(): Double? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.NUMBER -> nextDouble()
        JsonToken.STRING -> nextString().toDoubleOrNull()
        else -> { skipValue(); null }
    }
}
