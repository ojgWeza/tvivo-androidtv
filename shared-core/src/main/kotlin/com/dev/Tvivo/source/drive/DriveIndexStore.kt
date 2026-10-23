package com.dev.Tvivo.source.drive

import com.dev.Tvivo.source.IndexMetadata
import com.dev.Tvivo.source.IndexPage
import com.dev.Tvivo.source.IndexStore
import com.dev.Tvivo.source.SourceAccessToken
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.time.Instant

data class DriveHttpRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null)
data class DriveHttpResponse(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = byteArrayOf())
fun interface DriveHttpClient { suspend fun execute(request: DriveHttpRequest): DriveHttpResponse }

sealed class DriveFailure(message: String) : Exception(message) {
    data object Unauthorized : DriveFailure("Google Drive access expired. Sign in again")
    data object QuotaExceeded : DriveFailure("Drive is full. No library files were changed")
    data object Forbidden : DriveFailure("Tvivo cannot access this library")
    data class RateLimited(val retryAfterMillis: Long?) : DriveFailure("Drive is busy. We'll retry later")
    data object Transient : DriveFailure("Can't refresh now. Showing saved titles.")
    data object Offline : DriveFailure("Can't refresh now. Showing saved titles.")
}

/** Drive v3 files API. Redirect following belongs to the guarded media transport. */
class DriveIndexStore(
    private val http: DriveHttpClient,
    private val token: suspend () -> SourceAccessToken,
    private val baseUrl: String = "https://www.googleapis.com/drive/v3",
) : IndexStore {
    private val gson = Gson()
    override suspend fun listChildren(parentId: String?, pageToken: String?): IndexPage {
        val parent = if (parentId == null) "'root' in parents" else "'${escape(parentId)}' in parents"
        val query = "trashed = false and $parent"
        val params = mutableListOf("q=${enc(query)}", "fields=${enc("nextPageToken,files(id,name,mimeType,size,modifiedTime,version,md5Checksum)")}", "pageSize=1000")
        pageToken?.let { params += "pageToken=${enc(it)}" }
        val root = parse(api("GET", "$baseUrl/files?${params.joinToString("&")}"))
        return IndexPage(root.getAsJsonArray("files")?.map { metadata(it.asJsonObject) }.orEmpty(),
            root.get("nextPageToken")?.takeUnless { it.isJsonNull }?.asString)
    }
    override suspend fun createFolder(parentId: String?, name: String) = create(parentId, name, FOLDER_MIME, null)
    override suspend fun readIndex(fileId: String) = api("GET", "$baseUrl/files/${enc(fileId)}?alt=media")
    override suspend fun writeIndex(parentId: String, name: String, bytes: ByteArray): IndexMetadata {
        require(bytes.size <= 256 * 1024)
        val existing = listAll(parentId).firstOrNull { it.name == name && it.mimeType == JSON_MIME }
        if (existing != null) throw IllegalStateException("Refusing to overwrite existing index '$name'")
        return create(parentId, name, JSON_MIME, bytes)
    }
    override suspend fun metadata(fileId: String): IndexMetadata = metadata(parse(api("GET",
        "$baseUrl/files/${enc(fileId)}?fields=${enc("id,name,mimeType,size,modifiedTime,version,md5Checksum")}")))

    suspend fun listAll(parentId: String?): List<IndexMetadata> {
        val all = mutableListOf<IndexMetadata>(); var page: String? = null
        do { val next = listChildren(parentId, page); all += next.files; page = next.nextPageToken } while (page != null)
        return all
    }
    private suspend fun create(parentId: String?, name: String, mime: String, bytes: ByteArray?): IndexMetadata {
        val body = JsonObject().apply { addProperty("name", name); addProperty("mimeType", mime); if (parentId != null) add("parents", gson.toJsonTree(listOf(parentId))) }
        val created = parse(api("POST", "$baseUrl/files?uploadType=multipart", body.toString().toByteArray(), "application/json"))
        val id = created.get("id")?.asString ?: throw DriveFailure.Transient
        if (bytes != null) api("PATCH", "$baseUrl/files/${enc(id)}?uploadType=media", bytes, "application/json; charset=utf-8")
        return metadata(parse(api("GET", "$baseUrl/files/${enc(id)}?fields=${enc("id,name,mimeType,size,modifiedTime,version,md5Checksum")}")))
    }
    private suspend fun api(method: String, url: String, body: ByteArray? = null, contentType: String? = null): ByteArray {
        val headers = buildMap { put("Authorization", "Bearer ${token().value}"); contentType?.let { put("Content-Type", it) } }
        val response = try { http.execute(DriveHttpRequest(method, url, headers, body)) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { throw DriveFailure.Offline }
        if (response.status in 200..299) return response.body
        val reason = runCatching { JsonParser.parseString(response.body.toString(Charsets.UTF_8)).asJsonObject
            .getAsJsonObject("error").getAsJsonArray("errors").first().asJsonObject.get("reason").asString }.getOrNull()
        val retry = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.toLongOrNull()?.times(1000)
        throw when { response.status == 401 -> DriveFailure.Unauthorized
            response.status == 403 && reason == "storageQuotaExceeded" -> DriveFailure.QuotaExceeded
            response.status == 403 -> DriveFailure.Forbidden
            response.status == 429 -> DriveFailure.RateLimited(retry)
            response.status >= 500 -> DriveFailure.Transient
            else -> DriveFailure.Transient }
    }
    private fun parse(bytes: ByteArray): JsonObject = runCatching { JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject }.getOrElse { throw DriveFailure.Transient }
    private fun metadata(obj: JsonObject) = IndexMetadata(obj.get("id")?.asString ?: throw DriveFailure.Transient,
        obj.get("name")?.asString ?: "", obj.get("mimeType")?.asString ?: "",
        obj.get("size")?.takeUnless { it.isJsonNull }?.asLong,
        obj.get("modifiedTime")?.takeUnless { it.isJsonNull }?.asString?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        obj.get("version")?.takeUnless { it.isJsonNull }?.asString, obj.get("md5Checksum")?.takeUnless { it.isJsonNull }?.asString)
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")
    companion object { const val FOLDER_MIME = "application/vnd.google-apps.folder"; const val JSON_MIME = "application/json" }
}
