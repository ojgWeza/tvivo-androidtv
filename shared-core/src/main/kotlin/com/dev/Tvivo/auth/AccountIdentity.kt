package com.dev.Tvivo.auth

import java.security.MessageDigest
import java.util.Locale

object AccountIdentity {
    fun of(credentials: Credentials): String = of(credentials.host, credentials.port, credentials.username)

    fun of(host: String, port: Int?, username: String): String {
        val canonical = "${host.trim().lowercase(Locale.ROOT)}|${port ?: ""}|${username.trim()}"
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }
}
