package com.dev.Tvivo.source

/** Provider file metadata. A missing fingerprint means the adapter must download to compare. */
data class IndexMetadata(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val version: String?,
    val md5Checksum: String?
)

data class IndexPage(val files: List<IndexMetadata>, val nextPageToken: String?)

/** Filesystem-shaped boundary shared by Drive and a later OneDrive adapter. */
interface IndexStore {
    suspend fun listChildren(parentId: String?, pageToken: String? = null): IndexPage
    suspend fun createFolder(parentId: String?, name: String): IndexMetadata
    suspend fun readIndex(fileId: String): ByteArray
    suspend fun writeIndex(parentId: String, name: String, bytes: ByteArray): IndexMetadata
    suspend fun metadata(fileId: String): IndexMetadata
}

/** Access tokens are ephemeral. Persisting a TV refresh token belongs to its Android adapter. */
class SourceAccessToken(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = "SourceAccessToken(***redacted***)"
}

sealed interface AuthorizationStep {
    data object Ready : AuthorizationStep
    data class PhoneConsent(val requestId: String) : AuthorizationStep
    data class DeviceCode(
        val verificationUrl: String,
        val userCode: String,
        val expiresAtMillis: Long,
        val pollIntervalSeconds: Int
    ) : AuthorizationStep
    data object Pending : AuthorizationStep
    data object Denied : AuthorizationStep
    data object Expired : AuthorizationStep
    data object ReauthRequired : AuthorizationStep
}

/** One instance per candidate source; UI owns consent resolution and cancellation. */
interface SourceAuthorization {
    suspend fun begin(): AuthorizationStep
    suspend fun continueAuthorization(): AuthorizationStep
    suspend fun accessToken(): SourceAccessToken?
    suspend fun revoke(): Boolean
}
