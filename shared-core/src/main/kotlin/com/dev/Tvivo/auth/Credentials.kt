package com.dev.Tvivo.auth

data class Credentials(
    val host: String,
    val port: Int?,
    val username: String,
    val password: String,
    val useHttps: Boolean = false,
) {
    fun baseUrl(): String {
        val scheme = if (useHttps) "https" else "http"
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$scheme://$hostPart${port?.let { ":$it" } ?: ""}"
    }

    fun playerApiUrl(): String = "${baseUrl()}/player_api.php"

    fun hostAndPort(): String = port?.let { "$host:$it" } ?: host
}

object ServerAddress {
    data class Parsed(val host: String, val port: Int?, val useHttps: Boolean)

    fun parse(raw: String): Parsed? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        var https = false
        when {
            value.startsWith("http://", ignoreCase = true) -> value = value.drop(7)
            value.startsWith("https://", ignoreCase = true) -> {
                https = true
                value = value.drop(8)
            }
        }
        value = value.substringBefore('/').substringBefore('?').trim()
        if (value.isEmpty()) return null
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close < 0) return null
            val port = value.substring(close + 1).let { suffix ->
                if (suffix.isEmpty()) null else if (suffix.startsWith(":")) suffix.drop(1).toIntOrNull() else return null
            }
            return valid(value.substring(1, close), port, https)
        }
        if (value.count { it == ':' } > 1) return valid(value, null, https)
        val portText = value.substringAfter(':', "")
        return valid(value.substringBefore(':'), if (portText.isEmpty()) null else portText.toIntOrNull() ?: return null, https)
    }

    private fun valid(host: String, port: Int?, useHttps: Boolean): Parsed? =
        if (host.isBlank() || port != null && port !in 1..65535) null else Parsed(host, port, useHttps)
}
