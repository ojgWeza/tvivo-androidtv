package com.dev.Tvivo.data.remote

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.ResponseBody
import java.io.InputStreamReader

/**
 * The streaming JSON reader behind both `get_vod_streams` and `get_live_streams`.
 *
 * The two responses are the same shape with one field renamed — VOD carries
 * `container_extension`, live carries `ext` — so the difference is absorbed here and
 * neither caller learns about it. [VodStreamParser] and [LiveStreamParser] are the
 * typed wrappers; this holds the parsing rules and the memory contract.
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

    /** One panel list item, before it becomes a VOD or live entity. */
    data class RawStream(
        val streamId: Int,
        val categoryId: String?,
        val name: String,
        val streamIcon: String?,
        /** `container_extension` on VOD, `ext` on live — whichever the item carried. */
        val extension: String?,
        val added: Long?,
        val num: Int?
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

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "stream_id" -> streamId = reader.nextIntOrNull()
                "category_id" -> categoryId = reader.nextStringOrNull()
                "name" -> name = reader.nextStringOrNull()
                "stream_icon" -> icon = reader.nextStringOrNull()
                "container_extension", "ext" -> extension = reader.nextStringOrNull()
                "added" -> added = reader.nextStringOrNull()?.toLongOrNull()
                "num" -> num = reader.nextIntOrNull()
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
            num = num
        )
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
}
