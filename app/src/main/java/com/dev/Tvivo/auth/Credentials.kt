package com.dev.Tvivo.auth

/**
 * One credential set: server + port + username + password, stored together.
 * No host or port is ever defaulted from a provider — [ServerAddress.parse] only
 * fills a port when the user typed one, and `server_info` corrects it after auth.
 */
data class Credentials(
    val host: String,
    val port: Int?,
    val username: String,
    val password: String,
    val useHttps: Boolean = false
) {
    fun baseUrl(): String {
        val scheme = if (useHttps) "https" else "http"
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val portPart = port?.let { ":$it" } ?: ""
        return "$scheme://$hostPart$portPart"
    }

    fun playerApiUrl(): String = "${baseUrl()}/player_api.php"
}

/**
 * The login screen has one server field, not two. It accepts `host:port`,
 * `http://host:port`, `https://host:port/`, or a bare `host`, because that is how
 * credentials actually arrive — and because a second numeric field is unusable on
 * TVs whose IME ignores `KeyboardType.Number`.
 */
object ServerAddress {

    data class Parsed(val host: String, val port: Int?, val useHttps: Boolean)

    fun parse(raw: String): Parsed? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        var https = false
        when {
            s.startsWith("http://", ignoreCase = true) -> s = s.removeRange(0, 7)
            s.startsWith("https://", ignoreCase = true) -> {
                https = true
                s = s.removeRange(0, 8)
            }
        }

        s = s.substringBefore('/').substringBefore('?').trim()
        if (s.isEmpty()) return null

        // Bracketed IPv6: [::1]:8080
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            val host = s.substring(1, close)
            val rest = s.substring(close + 1)
            val port = if (rest.startsWith(":")) rest.drop(1).toIntOrNull() ?: return null else null
            return validated(host, port, https)
        }

        // Bare IPv6 without brackets has more than one colon and carries no port.
        if (s.count { it == ':' } > 1) return validated(s, null, https)

        val host = s.substringBefore(':')
        val portText = s.substringAfter(':', "")
        val port = if (portText.isEmpty()) null else portText.toIntOrNull() ?: return null
        return validated(host, port, https)
    }

    private fun validated(host: String, port: Int?, https: Boolean): Parsed? {
        if (host.isBlank()) return null
        if (port != null && port !in 1..65535) return null
        return Parsed(host, port, https)
    }
}
