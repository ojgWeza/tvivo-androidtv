package com.dev.Tvivo.data.remote

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import okhttp3.ResponseBody
import java.io.InputStreamReader

/**
 * Streams a `get_vod_streams` response straight into entities, in chunks.
 *
 * A full-catalog response is ~15 MB of JSON; parsed into a `List<T>` that is 30–50 MB of
 * heap on a box whose per-app limit may be 96 MB, *while* a Paging grid and a Coil bitmap
 * cache are live. So nothing here ever holds the whole response — [parse] hands each chunk
 * to [onChunk] and forgets it.
 *
 * Uses Gson's streaming reader rather than `android.util.JsonReader` so the parsing
 * rules — which is where the panel's field quirks live — are testable off-device.
 *
 * Fields are read defensively: `category_id` is a string on VOD items but an int on series
 * objects, `custom_sid` can be null, and `rating` is frequently 0 or absent.
 */
object VodStreamParser {

    suspend fun parse(
        body: ResponseBody,
        accountId: String,
        generation: Long,
        chunkSize: Int = 500,
        onChunk: suspend (List<VodStreamEntity>) -> Unit
    ): Int {
        var total = 0
        val buffer = ArrayList<VodStreamEntity>(chunkSize)
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
                readItem(reader, accountId, generation)?.let { buffer.add(it) }
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

    private fun readItem(
        reader: JsonReader,
        accountId: String,
        generation: Long
    ): VodStreamEntity? {
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
                "container_extension" -> extension = reader.nextStringOrNull()
                "added" -> added = reader.nextStringOrNull()?.toLongOrNull()
                "num" -> num = reader.nextIntOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (streamId == null || name == null) return null

        val names = NameNormalizer.of(name)
        return VodStreamEntity(
            accountId = accountId,
            streamId = streamId,
            categoryId = categoryId,
            name = names.raw,
            nameDisplay = names.display,
            nameNormalized = names.normalized,
            streamIcon = icon,
            containerExtension = extension,
            added = added,
            num = num,
            generation = generation
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
