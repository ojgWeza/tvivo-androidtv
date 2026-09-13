package com.dev.Tvivo.data

import com.dev.Tvivo.auth.Credentials
import java.net.URLEncoder

object StreamUrlBuilder {
    fun movie(credentials: Credentials, streamId: Int, extension: String): String = build(credentials, "movie", streamId, extension)
    fun live(credentials: Credentials, streamId: Int, extension: String): String = build(credentials, "live", streamId, extension)
    fun episode(credentials: Credentials, episodeId: String, extension: String): String =
        "${credentials.baseUrl()}/series/${encode(credentials.username)}/${encode(credentials.password)}/$episodeId.${extension(extension)}"

    fun redact(uri: String): String = Regex("/(live|movie|series)/([^/]+)/([^/]+)/").replace(uri) { match ->
        "/${match.groupValues[1]}/***/***/"
    }

    private fun build(credentials: Credentials, type: String, id: Int, extension: String): String =
        "${credentials.baseUrl()}/$type/${encode(credentials.username)}/${encode(credentials.password)}/$id.${extension(extension)}"
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun extension(value: String): String = value.trim().removePrefix(".").ifEmpty { "ts" }
}
