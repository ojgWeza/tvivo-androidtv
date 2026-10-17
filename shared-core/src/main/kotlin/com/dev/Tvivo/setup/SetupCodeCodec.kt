package com.dev.Tvivo.setup

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

object SetupCodeCodec {
    private const val MAX_INPUT = 65_536
    private const val MAX_CODE = 8_192
    private const val MAX_ENTITLEMENT = 2_048
    private val uriPattern = Regex("[A-Za-z][A-Za-z0-9+.-]*://setup/v[0-9]+(?:\\?[^\\s<>\"']*)?", RegexOption.IGNORE_CASE)
    private val tokenPattern = Regex("(?:${Regex.escape(SetupCodeFormat.BASE64_PREFIX)})?[A-Za-z0-9_-]{24,}={0,2}")
    private val wrappers = ".,;!?)]}"

    /** URI form. This deliberately exposes the secret, so callers must handle the result as a password. */
    fun encode(code: SetupCode): String {
        val checked = checked(code)
        val fields = listOfNotNull(
            "v" to checked.version,
            "name" to checked.name,
            "server" to checked.server.address(),
            "user" to checked.user,
            "pass" to checked.pass,
            checked.entitlement?.let { "ent" to it },
        )
        val query = fields.joinToString("&") { (key, value) -> "$key=${percentEncode(value)}" }
        val encoded = "${SetupCodeFormat.SCHEME}://setup/v1?$query"
        require(encoded.length <= MAX_CODE) { "Setup code is too long" }
        return encoded
    }

    /** Alternate transport for systems that cannot preserve a URI. */
    fun encodeBase64Url(code: SetupCode): String {
        val checked = checked(code)
        val fields = listOfNotNull(
            "v" to checked.version,
            "name" to checked.name,
            "server" to checked.server.address(),
            "user" to checked.user,
            "pass" to checked.pass,
            checked.entitlement?.let { "ent" to it },
        )
        val json = fields.joinToString(",", "{", "}") { (key, value) -> "\"$key\":${jsonString(value)}" }
        val encoded = SetupCodeFormat.BASE64_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        require(encoded.length <= MAX_CODE) { "Setup code is too long" }
        return encoded
    }

    /** Finds the first *valid* code in pasted text; a preceding malformed candidate is skipped. */
    fun decode(input: String): SetupCodeResult {
        if (input.length > MAX_INPUT) return SetupCodeResult.Invalid("Input is too long")
        val candidates = (uriPattern.findAll(input).map { it.range.first to it.value } +
            tokenPattern.findAll(input).map { it.range.first to it.value })
            .sortedBy { it.first }.toList()
        var firstFailure: String? = null
        for ((_, raw) in candidates) {
            var candidate = raw
            do {
                val result = decodeCandidate(candidate)
                if (result is SetupCodeResult.Ok) return result
                if (firstFailure == null) firstFailure = (result as SetupCodeResult.Invalid).reason
                val shorter = candidate.trimEnd { it in wrappers }
                if (shorter == candidate) break
                candidate = shorter
            } while (true)
        }
        return SetupCodeResult.Invalid(firstFailure ?: "No setup code found")
    }

    private fun decodeCandidate(candidate: String): SetupCodeResult {
        if (candidate.length > MAX_CODE) return SetupCodeResult.Invalid("Setup code is too long")
        return try {
            val code = if (candidate.contains("://setup/", ignoreCase = true)) fromUri(candidate)
                else fromBase64(candidate)
            SetupCodeResult.Ok(checked(code))
        } catch (bad: BadCode) {
            SetupCodeResult.Invalid(bad.reason)
        }
    }

    private fun fromUri(text: String): SetupCode {
        for (i in text.indices) if (text[i] == '%' &&
            (i + 2 >= text.length || text[i + 1].digitToIntOrNull(16) == null ||
                text[i + 2].digitToIntOrNull(16) == null)) invalid("Malformed percent encoding")
        val uri = try { URI(text) } catch (_: Exception) { invalid("Malformed setup URI") }
        if (!uri.scheme.equals(SetupCodeFormat.SCHEME, ignoreCase = true)) invalid("Unsupported setup scheme")
        if (uri.rawAuthority != "setup" || uri.rawFragment != null) invalid("Malformed setup URI")
        val major = uri.path.removePrefix("/v").toIntOrNull() ?: invalid("Malformed setup version")
        if (uri.path != "/v$major" || major != 1) invalid("Unsupported setup version")
        val fields = linkedMapOf<String, String>()
        val query = uri.rawQuery ?: invalid("Missing setup fields")
        for (part in query.split('&')) {
            val equal = part.indexOf('=')
            if (equal < 1) invalid("Malformed setup field")
            val key = percentDecode(part.substring(0, equal))
            if (fields.putIfAbsent(key, percentDecode(part.substring(equal + 1))) != null) {
                invalid("Duplicate setup field")
            }
        }
        return fromFields(fields, major)
    }

    private fun fromBase64(text: String): SetupCode {
        val token = text.removePrefix(SetupCodeFormat.BASE64_PREFIX)
        val bytes = try { Base64.getUrlDecoder().decode(token) } catch (_: IllegalArgumentException) {
            invalid("Malformed setup JSON token")
        }
        if (bytes.size > MAX_CODE) invalid("Setup code is too long")
        val json = utf8(bytes)
        val fields = JsonFields(json).read()
        return fromFields(fields, null)
    }

    private fun fromFields(fields: Map<String, String>, pathMajor: Int?): SetupCode {
        val version = parseVersion(fields["v"] ?: if (pathMajor == 1) SetupCodeFormat.VERSION else invalid("Missing setup version"))
        if (pathMajor != null && !version.startsWith("$pathMajor.")) invalid("Conflicting setup version")
        return SetupCode(
            version = version,
            name = fields["name"] ?: invalid("Missing source name"),
            server = parseServer(fields["server"] ?: invalid("Missing server")),
            user = fields["user"] ?: invalid("Missing username"),
            pass = fields["pass"] ?: invalid("Missing password"),
            entitlement = fields["ent"],
        )
    }

    private fun checked(code: SetupCode): SetupCode {
        val version = parseVersion(code.version)
        val name = code.name.filterNot { Character.isISOControl(it) || it.isBidiFormat() }.trim()
        if (name.isEmpty() || name.codePointCount(0, name.length) > 40) invalid("Invalid source name")
        if (code.user.isBlank() || code.user.codePointCount(0, code.user.length) > 128) invalid("Invalid username")
        if (code.pass.isBlank() || code.pass.codePointCount(0, code.pass.length) > 128) invalid("Invalid password")
        if (code.entitlement != null && code.entitlement.length > MAX_ENTITLEMENT) invalid("Invalid entitlement")
        return code.copy(version = version, name = name, server = parseServer(code.server.address()))
    }

    private fun parseVersion(raw: String): String {
        val match = Regex("([0-9]+)(?:\\.([0-9]+))?").matchEntire(raw) ?: invalid("Malformed setup version")
        val major = match.groupValues[1].toIntOrNull() ?: invalid("Unsupported setup version")
        if (major != 1) invalid("Unsupported setup version")
        val minor = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: invalid("Malformed setup version")
        if (minor > 999) invalid("Malformed setup version")
        return "1.$minor"
    }

    private fun parseServer(raw: String): SetupServer {
        var value = raw
        val scheme = when {
            value.startsWith("https://", ignoreCase = true) -> { value = value.substring(8); "https" }
            value.startsWith("http://", ignoreCase = true) -> { value = value.substring(7); "http" }
            "://" in value -> invalid("Unsupported server scheme")
            else -> "http"
        }
        if (value.isEmpty() || value.any { it.isWhitespace() || Character.isISOControl(it) || it in "/?#@\\%" }) {
            invalid("Invalid server host")
        }
        val host: String
        val portText: String?
        if (value.startsWith('[')) {
            val close = value.indexOf(']')
            if (close < 0) invalid("Invalid server host")
            host = value.substring(1, close)
            val suffix = value.substring(close + 1)
            portText = when {
                suffix.isEmpty() -> null
                suffix.startsWith(':') -> suffix.substring(1)
                else -> invalid("Invalid server host")
            }
            if (!validIpv6(host)) invalid("Invalid server host")
        } else {
            if (value.count { it == ':' } > 1) invalid("Bracket IPv6 server hosts")
            host = value.substringBefore(':')
            portText = if (':' in value) value.substringAfter(':') else null
            if (host.length > 253 || host.isEmpty() || host.split('.').any { label ->
                    label.isEmpty() || label.length > 63 ||
                        !label.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
                }) invalid("Invalid server host")
        }
        if (host.length > 253) invalid("Invalid server host")
        val port = when {
            portText == null || portText.isEmpty() -> null
            !portText.all(Char::isDigit) -> invalid("Invalid server port")
            else -> portText.toIntOrNull() ?: invalid("Invalid server port")
        }
        if (port != null && port !in 1..65535) invalid("Invalid server port")
        return SetupServer(host, port, scheme)
    }

    private fun validIpv6(host: String): Boolean {
        if (!host.matches(Regex("[0-9A-Fa-f:]+")) || ":::" in host) return false
        val compressed = "::" in host
        if (compressed && host.indexOf("::") != host.lastIndexOf("::")) return false
        val groups = if (compressed) {
            val halves = host.split("::", limit = 2)
            listOf(halves[0], halves[1]).flatMap { half ->
                if (half.isEmpty()) emptyList() else half.split(':')
            }
        } else host.split(':')
        return groups.all { it.length in 1..4 && it.all { char -> char.isHexDigit() } } &&
            if (compressed) groups.size < 8 else groups.size == 8
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun percentEncode(value: String): String = buildString {
        for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
            val n = byte.toInt() and 0xff
            if (n in 65..90 || n in 97..122 || n in 48..57 || n in listOf(45, 46, 95, 126)) {
                append(n.toChar())
            } else {
                append('%').append("0123456789ABCDEF"[n shr 4]).append("0123456789ABCDEF"[n and 15])
            }
        }
    }

    private fun percentDecode(value: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            when (value[i]) {
                '%' -> {
                    if (i + 2 >= value.length) invalid("Malformed percent encoding")
                    val high = value[i + 1].digitToIntOrNull(16) ?: invalid("Malformed percent encoding")
                    val low = value[i + 2].digitToIntOrNull(16) ?: invalid("Malformed percent encoding")
                    out.write(high * 16 + low)
                    i += 3
                }
                '+' -> { out.write(32); i++ }
                else -> {
                    val point = Character.codePointAt(value, i)
                    out.write(String(Character.toChars(point)).toByteArray(StandardCharsets.UTF_8))
                    i += Character.charCount(point)
                }
            }
        }
        return utf8(out.toByteArray())
    }

    private fun utf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { invalid("Malformed UTF-8") }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (char in value) when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
        append('"')
    }

    private fun Char.isBidiFormat(): Boolean =
        this == '\u061c' || this in '\u200e'..'\u200f' || this in '\u202a'..'\u202e' || this in '\u2066'..'\u2069'

    private class BadCode(val reason: String) : RuntimeException()
    private fun invalid(reason: String): Nothing = throw BadCode(reason)

    /** Small strict JSON reader so shared-core does not take a runtime JSON dependency. */
    private class JsonFields(private val text: String) {
        private var at = 0

        fun read(): Map<String, String> {
            skipSpace()
            expect('{')
            val fields = linkedMapOf<String, String>()
            val keys = mutableSetOf<String>()
            skipSpace()
            if (take('}')) return fields
            do {
                skipSpace()
                val key = string()
                skipSpace()
                expect(':')
                skipSpace()
                val quoted = peek() == '"'
                val value = value(0)
                if (!keys.add(key)) invalid("Duplicate setup field")
                if (key in setOf("v", "name", "server", "user", "pass", "ent")) {
                    if (!quoted && key != "v") invalid("Malformed setup field")
                    fields[key] = value ?: invalid("Malformed setup field")
                }
                skipSpace()
                if (take('}')) break
                expect(',')
            } while (true)
            skipSpace()
            if (at != text.length) invalid("Malformed setup JSON")
            return fields
        }

        private fun value(depth: Int): String? {
            if (depth > 8) invalid("Malformed setup JSON")
            skipSpace()
            return when (peek()) {
                '"' -> string()
                '{' -> {
                    at++
                    skipSpace()
                    if (!take('}')) {
                        do {
                            skipSpace()
                            string()
                            skipSpace()
                            expect(':')
                            value(depth + 1)
                            skipSpace()
                            if (take('}')) break
                            expect(',')
                        } while (true)
                    }
                    null
                }
                '[' -> {
                    at++
                    skipSpace()
                    if (!take(']')) {
                        do {
                            value(depth + 1)
                            skipSpace()
                            if (take(']')) break
                            expect(',')
                        } while (true)
                    }
                    null
                }
                else -> scalar()
            }
        }

        private fun scalar(): String {
            val start = at
            while (at < text.length && text[at] !in ",}] \t\r\n") at++
            val value = text.substring(start, at)
            if (!value.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) &&
                value !in listOf("true", "false", "null")) invalid("Malformed setup JSON")
            return value
        }

        private fun string(): String = buildString {
            expect('"')
            while (true) {
                if (at >= text.length) invalid("Malformed setup JSON")
                val char = text[at++]
                when (char) {
                    '"' -> return@buildString
                    '\\' -> {
                        if (at >= text.length) invalid("Malformed setup JSON")
                        when (val escaped = text[at++]) {
                            '"', '\\', '/' -> append(escaped)
                            'b' -> append('\b')
                            'f' -> append('\u000c')
                            'n' -> append('\n')
                            'r' -> append('\r')
                            't' -> append('\t')
                            'u' -> {
                                if (at + 4 > text.length) invalid("Malformed setup JSON")
                                append(text.substring(at, at + 4).toIntOrNull(16)?.toChar()
                                    ?: invalid("Malformed setup JSON"))
                                at += 4
                            }
                            else -> invalid("Malformed setup JSON")
                        }
                    }
                    else -> if (char.code < 0x20) invalid("Malformed setup JSON") else append(char)
                }
            }
        }

        private fun skipSpace() { while (at < text.length && text[at] in " \t\r\n") at++ }
        private fun peek(): Char? = text.getOrNull(at)
        private fun take(char: Char): Boolean = if (peek() == char) { at++; true } else false
        private fun expect(char: Char) { if (!take(char)) invalid("Malformed setup JSON") }
    }
}
