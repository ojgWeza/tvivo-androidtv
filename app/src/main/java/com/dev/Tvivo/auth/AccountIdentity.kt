package com.dev.Tvivo.auth

import java.security.MessageDigest
import java.util.Locale

/**
 * Every cached row is scoped to an account. Pointing the app at a different panel,
 * or changing the username, must never surface the previous account's catalog,
 * artwork or resume positions — so the id has to be derived, not incremented.
 *
 * The password is deliberately excluded: changing a password is not a new account,
 * and including it would orphan the whole cache on every password rotation.
 */
object AccountIdentity {

    fun of(credentials: Credentials): String =
        of(credentials.host, credentials.port, credentials.username)

    fun of(host: String, port: Int?, username: String): String {
        val canonical = buildString {
            append(host.trim().lowercase(Locale.ROOT))
            append('|')
            append(port?.toString() ?: "")
            append('|')
            append(username.trim())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
