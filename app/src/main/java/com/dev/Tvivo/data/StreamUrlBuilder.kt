package com.dev.Tvivo.data

import com.dev.Tvivo.auth.Credentials
import java.net.URLEncoder

/**
 * All playback URLs are constructed client-side. `direct_source` is typically empty
 * on this panel, so it is never trusted.
 */
object StreamUrlBuilder {

    fun movie(c: Credentials, streamId: Int, containerExtension: String): String =
        build(c, "movie", streamId, containerExtension)

    fun live(c: Credentials, streamId: Int, ext: String): String =
        build(c, "live", streamId, ext)

    fun episode(c: Credentials, episodeId: String, containerExtension: String): String =
        "${c.baseUrl()}/series/${enc(c.username)}/${enc(c.password)}/$episodeId.${ext(containerExtension)}"

    private fun build(c: Credentials, path: String, id: Int, extension: String): String =
        "${c.baseUrl()}/$path/${enc(c.username)}/${enc(c.password)}/$id.${ext(extension)}"

    /**
     * Xtream puts credentials in the URL path, and ExoPlayer echoes the full URI in its
     * error messages. Every log line and every error surface must pass through this.
     */
    fun redact(uri: String): String =
        Regex("/(live|movie|series)/([^/]+)/([^/]+)/").replace(uri) { m ->
            "/${m.groupValues[1]}/***/***/"
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Panels occasionally return an extension with a leading dot or empty; normalise both. */
    private fun ext(raw: String): String =
        raw.trim().removePrefix(".").ifEmpty { "ts" }
}
