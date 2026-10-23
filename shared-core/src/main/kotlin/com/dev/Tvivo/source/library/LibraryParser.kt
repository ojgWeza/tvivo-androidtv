package com.dev.Tvivo.source.library

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.InetAddress
import java.net.URI
import java.util.UUID

private const val FORMAT = "tvivo.link-library"
private const val MAX_FILE_BYTES = 256 * 1024
private const val MAX_ITEMS = 1_000
private const val MAX_URL_LENGTH = 2 * 1024
private const val MAX_TEXT = 4_000

fun parseLibraryMarker(json: String): ParseResult<LibraryMarker> = parseDocument(json) { root ->
    val version = requiredInt(root, "version")
    if (version != 1) reject(RejectionReason.UNSUPPORTED_VERSION, "Unsupported library version: $version")
    LibraryMarker(requiredString(root, "format", 64), version, requiredString(root, "bootstrap", 32)).also {
        if (it.format != FORMAT) reject(RejectionReason.WRONG_FORMAT, "Unrecognized library format")
        if (it.bootstrap != "complete") reject(RejectionReason.INVALID_FIELD, "Library bootstrap marker is not complete")
    }
}

fun parseMovies(json: String, allowMockHttp: Boolean = false): ParseResult<ParsedList<MovieEntry>> =
    parseList(json, "movies", allowMockHttp) { root ->
        val ids = HashSet<String>()
        root.array("items").map { element ->
            val item = element.objectValue()
            val id = stableId(item)
            if (!ids.add(id)) reject(RejectionReason.DUPLICATE_ID, "Duplicate item id")
            MovieEntry(
                id, requiredString(item, "title", 256), requiredString(item, "group", 256),
                requiredUrl(item, "streamUrl", allowMockHttp), optionalUrl(item, "posterUrl", allowMockHttp),
                optionalYear(item), optionalString(item, "plot", MAX_TEXT),
            )
        }
    }

fun parseSeries(json: String, allowMockHttp: Boolean = false): ParseResult<ParsedList<ShowEntry>> =
    parseList(json, "series", allowMockHttp) { root ->
        val ids = HashSet<String>()
        var mediaItemCount = 0
        root.array("items").map { element ->
            if (++mediaItemCount > MAX_ITEMS) reject(RejectionReason.TOO_MANY_ITEMS, "Index has more than $MAX_ITEMS items")
            val item = element.objectValue()
            val id = stableId(item)
            if (!ids.add(id)) reject(RejectionReason.DUPLICATE_ID, "Duplicate show or episode id")
            val seasonsArray = item.get("seasons")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: reject(RejectionReason.MISSING_REQUIRED_FIELD, "Missing or invalid seasons")
            if (seasonsArray.size() == 0) reject(RejectionReason.INVALID_FIELD, "A series needs at least one season")
            val seasonNumbers = HashSet<Int>()
            val seasons = seasonsArray.map { seasonElement ->
                val season = seasonElement.objectValue()
                val number = requiredPositiveInt(season, "number")
                if (!seasonNumbers.add(number)) reject(RejectionReason.INVALID_FIELD, "Duplicate season number")
                val episodes = season.array("episodes").map { episodeElement ->
                    if (++mediaItemCount > MAX_ITEMS) reject(RejectionReason.TOO_MANY_ITEMS, "Index has more than $MAX_ITEMS items")
                    val episode = episodeElement.objectValue()
                    val episodeId = stableId(episode)
                    if (!ids.add(episodeId)) reject(RejectionReason.DUPLICATE_ID, "Duplicate show or episode id")
                    EpisodeEntry(
                        episodeId, requiredPositiveInt(episode, "number"), requiredString(episode, "title", 256),
                        requiredUrl(episode, "streamUrl", allowMockHttp), optionalUrl(episode, "posterUrl", allowMockHttp),
                        optionalString(episode, "plot", MAX_TEXT),
                    )
                }
                SeasonEntry(number, optionalString(season, "title", 256), episodes)
            }
            ShowEntry(
                id, requiredString(item, "title", 256), requiredString(item, "group", 256), seasons,
                optionalYear(item), optionalUrl(item, "posterUrl", allowMockHttp), optionalString(item, "plot", MAX_TEXT),
            )
        }
    }

fun parseMusic(json: String, allowMockHttp: Boolean = false): ParseResult<ParsedList<SongEntry>> =
    parseList(json, "music", allowMockHttp) { root ->
        val ids = HashSet<String>()
        root.array("items").map { element ->
            val item = element.objectValue()
            val id = stableId(item)
            if (!ids.add(id)) reject(RejectionReason.DUPLICATE_ID, "Duplicate item id")
            val duration = optionalInt(item, "durationSeconds")
            if (duration != null && duration <= 0) reject(RejectionReason.INVALID_FIELD, "durationSeconds must be positive")
            SongEntry(
                id, requiredString(item, "title", 256), requiredString(item, "group", 256),
                requiredUrl(item, "streamUrl", allowMockHttp), optionalString(item, "artist", 256),
                optionalString(item, "album", 256), optionalUrl(item, "artworkUrl", allowMockHttp), optionalYear(item), duration,
            )
        }
    }

private fun <T> parseList(json: String, kind: String, allowMockHttp: Boolean, parseItems: (JsonObject) -> List<T>): ParseResult<ParsedList<T>> =
    parseDocument(json) { root ->
        val version = requiredInt(root, "version")
        if (version != 1) reject(RejectionReason.UNSUPPORTED_VERSION, "Unsupported library version: $version")
        val format = requiredString(root, "format", 64)
        if (format != FORMAT) reject(RejectionReason.WRONG_FORMAT, "Unrecognized library format")
        if (requiredString(root, "kind", 32) != kind) reject(RejectionReason.WRONG_KIND, "Expected $kind index")
        val values = root.array("items")
        if (values.size() > MAX_ITEMS) reject(RejectionReason.TOO_MANY_ITEMS, "Index has more than $MAX_ITEMS items")
        ParsedList(kind, parseItems(root))
    }

private fun <T> parseDocument(json: String, block: (JsonObject) -> T): ParseResult<T> {
    if (json.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
        return ParseResult.Rejected(RejectionReason.TOO_LARGE, "Index exceeds 256 KiB")
    }
    return try {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            ?: reject(RejectionReason.MALFORMED_JSON, "Expected a JSON object")
        ParseResult.Valid(block(root))
    } catch (error: ParseRejected) {
        ParseResult.Rejected(error.reason, error.message ?: "Invalid library file")
    } catch (_: Exception) {
        ParseResult.Rejected(RejectionReason.MALFORMED_JSON, "Malformed JSON")
    }
}

private class ParseRejected(val reason: RejectionReason, message: String) : RuntimeException(message)
private fun reject(reason: RejectionReason, message: String): Nothing = throw ParseRejected(reason, message)
private fun JsonObject.array(name: String) = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
    ?: reject(RejectionReason.MISSING_REQUIRED_FIELD, "Missing or invalid $name")
private fun JsonElement.objectValue() = takeIf { isJsonObject }?.asJsonObject
    ?: reject(RejectionReason.INVALID_FIELD, "Expected object")
private fun requiredString(obj: JsonObject, name: String, max: Int): String {
    val value = obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: reject(RejectionReason.MISSING_REQUIRED_FIELD, "Missing or invalid $name")
    if (value.isBlank() || value.length > max) reject(RejectionReason.INVALID_FIELD, "Invalid $name length")
    return value
}
private fun optionalString(obj: JsonObject, name: String, max: Int): String? {
    val element = obj.get(name) ?: return null
    if (element.isJsonNull) return null
    val value = element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: reject(RejectionReason.INVALID_FIELD, "Invalid $name")
    if (value.length > max) reject(RejectionReason.INVALID_FIELD, "$name is too long")
    return value
}
private fun requiredInt(obj: JsonObject, name: String): Int = optionalInt(obj, name)
    ?: reject(RejectionReason.MISSING_REQUIRED_FIELD, "Missing or invalid $name")
private fun optionalInt(obj: JsonObject, name: String): Int? {
    val value = obj.get(name) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) reject(RejectionReason.INVALID_FIELD, "Invalid $name")
    return value.asInt.takeIf { value.asString == it.toString() }
        ?: reject(RejectionReason.INVALID_FIELD, "Invalid $name")
}
private fun requiredPositiveInt(obj: JsonObject, name: String) = requiredInt(obj, name).also {
    if (it <= 0) reject(RejectionReason.INVALID_FIELD, "$name must be positive")
}
private fun optionalYear(obj: JsonObject) = optionalInt(obj, "year")?.also {
    if (it !in 1800..2200) reject(RejectionReason.INVALID_FIELD, "Invalid year")
}
private fun stableId(obj: JsonObject): String {
    val value = requiredString(obj, "id", 36)
    val canonical = runCatching { UUID.fromString(value).toString() }.getOrNull()
    if (canonical == null || canonical != value || value.length != 36) reject(RejectionReason.INVALID_FIELD, "id must be a canonical lowercase UUID")
    return value
}
private fun requiredUrl(obj: JsonObject, name: String, allowMockHttp: Boolean) = requiredString(obj, name, MAX_URL_LENGTH).also {
    validateUrl(it, allowMockHttp)
}
private fun optionalUrl(obj: JsonObject, name: String, allowMockHttp: Boolean): String? = optionalString(obj, name, MAX_URL_LENGTH)?.also {
    validateUrl(it, allowMockHttp)
}
private fun validateUrl(value: String, allowMockHttp: Boolean) {
    val uri = runCatching { URI(value) }.getOrNull() ?: reject(RejectionReason.UNSAFE_URL, "Invalid URL")
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase() ?: reject(RejectionReason.UNSAFE_URL, "URL needs a host")
    if (uri.rawUserInfo != null || uri.port !in -1..65535 || uri.fragment != null) reject(RejectionReason.UNSAFE_URL, "Invalid URL authority")
    val loopback = host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    val localHttpAllowed = allowMockHttp && scheme == "http" && (loopback || isPrivateAddress(host))
    if (scheme !in setOf("https", "http") || scheme == "http" && !localHttpAllowed) reject(RejectionReason.UNSAFE_URL, "Only HTTPS URLs are supported")
    if (loopback && !localHttpAllowed) reject(RejectionReason.UNSAFE_URL, "Local URLs are not supported")
    if (host.endsWith(".local") || isPrivateAddress(host)) {
        if (!localHttpAllowed) reject(RejectionReason.UNSAFE_URL, "Private network URLs are not supported")
    }
}
private fun isPrivateAddress(host: String): Boolean {
    val literal = host.removePrefix("[").removeSuffix("]")
    val ipv4 = literal.split('.').takeIf { it.size == 4 }?.mapNotNull { it.toIntOrNull() }
    if (ipv4?.size == 4) {
        val a = ipv4[0]; val b = ipv4[1]
        return a == 0 || a == 10 || a == 127 || a == 169 && b == 254 || a == 172 && b in 16..31 || a == 192 && b == 168 || a >= 224
    }
    if (literal.contains(':')) {
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return true
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
    }
    return false
}
