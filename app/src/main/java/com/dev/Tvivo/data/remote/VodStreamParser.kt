package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import okhttp3.ResponseBody

/**
 * `get_vod_streams` → [VodStreamEntity], in chunks.
 *
 * The reading is [StreamListParser]'s — shared with live, which is the same response
 * shape with `container_extension` renamed to `ext`. This layer is the VOD field mapping
 * and nothing else.
 */
object VodStreamParser {

    suspend fun parse(
        body: ResponseBody,
        accountId: String,
        generation: Long,
        chunkSize: Int = 500,
        onChunk: suspend (List<VodStreamEntity>) -> Unit
    ): Int = StreamListParser.parse(
        body = body,
        chunkSize = chunkSize,
        map = { raw ->
            val names = NameNormalizer.of(raw.name)
            VodStreamEntity(
                accountId = accountId,
                streamId = raw.streamId,
                categoryId = raw.categoryId,
                name = names.raw,
                nameDisplay = names.display,
                nameNormalized = names.normalized,
                streamIcon = raw.streamIcon,
                containerExtension = raw.extension,
                plot = raw.plot,
                rating = raw.rating,
                added = raw.added,
                num = raw.num,
                generation = generation
            )
        },
        onChunk = onChunk
    )
}
