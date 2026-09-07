package com.dev.Tvivo.data.model

import com.google.gson.annotations.SerializedName

data class AuthResponse(
    @SerializedName("user_info") val userInfo: UserInfo?,
    @SerializedName("server_info") val serverInfo: ServerInfo?
)

data class UserInfo(
    @SerializedName("username") val username: String?,
    @SerializedName("auth") val auth: Int?,
    @SerializedName("status") val status: String?,
    @SerializedName("exp_date") val expDate: String?,
    @SerializedName("is_trial") val isTrial: String?,
    @SerializedName("active_cons") val activeConnections: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("max_connections") val maxConnections: String?,
    @SerializedName("allowed_output_formats") val allowedOutputFormats: List<String>?
) {
    val isAuthenticated: Boolean get() = auth == 1
    val isActive: Boolean get() = status.equals("Active", ignoreCase = true)
    val expiresAtEpochSeconds: Long? get() = expDate?.toLongOrNull()
}

/**
 * The panel reports its own ports back. The login screen only asks for one, so this
 * is where a missing or wrong port gets corrected after a successful auth.
 */
data class ServerInfo(
    @SerializedName("url") val url: String?,
    @SerializedName("port") val port: String?,
    @SerializedName("https_port") val httpsPort: String?,
    @SerializedName("server_protocol") val serverProtocol: String?,
    @SerializedName("rtmp_port") val rtmpPort: String?,
    @SerializedName("timezone") val timezone: String?,
    @SerializedName("timestamp_now") val timestampNow: Long?,
    @SerializedName("time_now") val timeNow: String?
)
