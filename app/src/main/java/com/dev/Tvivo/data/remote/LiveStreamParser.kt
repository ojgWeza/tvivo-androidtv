package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.LiveStreamEntity
import okhttp3.ResponseBody

/**
 * `get_live_streams` → [LiveStreamEntity], in chunks.
 *
 * Same reader as VOD ([StreamListParser]); the only live-specific thing is that the
 * playback extension arrives as `ext` rather than `container_extension`, which the
 * shared reader already accepts under either name.
 */
object LiveStreamParser {

    suspend fun parse(
        body: ResponseBody,
        accountId: String,
        generation: Long,
        chunkSize: Int = 500,
        onChunk: suspend (List<LiveStreamEntity>) -> Unit
    ): Int = StreamListParser.parse(
        body = body,
        chunkSize = chunkSize,
        map = { raw ->
            val names = NameNormalizer.of(raw.name)
            LiveStreamEntity(
                accountId = accountId,
                streamId = raw.streamId,
                categoryId = raw.categoryId,
                name = names.raw,
                nameDisplay = names.display,
                nameNormalized = names.normalized,
                streamIcon = raw.streamIcon,
                ext = raw.extension,
                added = raw.added,
                num = raw.num,
                generation = generation
            )
        },
        onChunk = onChunk
    )
}
