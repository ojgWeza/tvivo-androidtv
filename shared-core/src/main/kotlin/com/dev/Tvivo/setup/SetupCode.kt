package com.dev.Tvivo.setup

/** A source supplied by a person or a setup QR. The entitlement is untrusted opaque data. */
data class SetupCode(
    val version: String = SetupCodeFormat.VERSION,
    val name: String,
    val server: SetupServer,
    val user: String,
    val pass: String,
    val entitlement: String? = null,
) {
    override fun toString(): String =
        "SetupCode(version=$version, name=<redacted>, server=<redacted>, user=<redacted>, pass=<redacted>, entitlement=<redacted>)"
}

data class SetupServer(val host: String, val port: Int? = null, val scheme: String = "http") {
    fun address(): String {
        val hostPart = if (host.contains(':')) "[$host]" else host
        return "$scheme://$hostPart${port?.let { ":$it" } ?: ""}"
    }
}

sealed interface SetupCodeResult {
    data class Ok(val code: SetupCode) : SetupCodeResult
    data class Invalid(val reason: String) : SetupCodeResult
}

object SetupCodeFormat {
    /** Replace this one value when the product name is decided. */
    const val SCHEME = "sourcecode"
    const val VERSION = "1.0"
    const val BASE64_PREFIX = "$SCHEME-setup:"
}
