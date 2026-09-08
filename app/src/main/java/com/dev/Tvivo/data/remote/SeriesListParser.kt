package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.local.NameNormalizer
import com.dev.Tvivo.data.local.entities.SeriesEntity
import okhttp3.ResponseBody

/**
 * `get_series` → [SeriesEntity], in chunks.
 *
 * Same reader as VOD and live ([StreamListParser]); the series-specific part is only
 * that the panel calls the id `series_id`, the poster `cover` and the timestamp
 * `last_modified`, which the shared reader already accepts under either name.
 */
object SeriesListParser {

    suspend fun parse(
        body: ResponseBody,
        accountId: String,
        generation: Long,
        chunkSize: Int = 500,
        onChunk: suspend (List<SeriesEntity>) -> Unit
    ): Int = StreamListParser.parse(
        body = body,
        chunkSize = chunkSize,
        map = { raw ->
            val names = NameNormalizer.of(raw.name)
            SeriesEntity(
                accountId = accountId,
                seriesId = raw.streamId,
                categoryId = raw.categoryId,
                name = names.raw,
                nameDisplay = names.display,
                nameNormalized = names.normalized,
                streamIcon = raw.streamIcon,
                plot = raw.plot?.takeIf { it.isNotBlank() },
                added = raw.added,
                num = raw.num,
                generation = generation
            )
        },
        onChunk = onChunk
    )
}
