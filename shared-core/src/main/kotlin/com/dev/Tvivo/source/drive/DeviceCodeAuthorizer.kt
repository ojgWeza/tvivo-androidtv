package com.dev.Tvivo.source.drive

import com.dev.Tvivo.source.AuthorizationStep
import com.dev.Tvivo.source.SourceAccessToken
import com.dev.Tvivo.source.SourceAuthorization
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DeviceCodeEndpoints(
    val codeUrl: String = "https://oauth2.googleapis.com/device/code",
    val tokenUrl: String = "https://oauth2.googleapis.com/token",
    val revokeUrl: String = "https://oauth2.googleapis.com/revoke"
)

/** Values are parsed by the transport; error replies use [error] rather than throwing for HTTP 400. */
data class OAuthReply(
    val deviceCode: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val expiresIn: Long? = null,
    val interval: Int? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val error: String? = null
)

interface DeviceCodeHttp {
    suspend fun post(url: String, form: Map<String, String>): OAuthReply
}

fun interface AuthClock { fun nowMillis(): Long }

/** Implementations must persist a replacement refresh token before returning from [save]. */
interface RefreshTokenStore {
    suspend fun load(): String?
    suspend fun save(token: String?)
}

class DeviceCodeAuthorizer(
    private val clientId: String,
    private val http: DeviceCodeHttp,
    private val tokens: RefreshTokenStore,
    private val clock: AuthClock,
    private val endpoints: DeviceCodeEndpoints = DeviceCodeEndpoints()
) : SourceAuthorization {
    private val lock = Mutex()
    private var deviceCode: String? = null
    private var expiry = 0L
    private var intervalMillis = 0L
    private var nextPoll = 0L
    private var access: SourceAccessToken? = null
    private var accessExpiry = 0L
    private var cancelled = false
    private var reauth = false

    override suspend fun begin(): AuthorizationStep = lock.withLock {
        require(clientId.isNotBlank()) { "TV OAuth client ID is not configured" }
        cancelled = false
        reauth = false
        access = null
        deviceCode = null
        val reply = http.post(endpoints.codeUrl, mapOf(
            "client_id" to clientId,
            "scope" to "https://www.googleapis.com/auth/drive.file"
        ))
        if (reply.error != null) return@withLock AuthorizationStep.Denied
        val code = requireNotNull(reply.deviceCode)
        val user = requireNotNull(reply.userCode)
        val url = requireNotNull(reply.verificationUrl)
        val seconds = requireNotNull(reply.expiresIn).coerceAtLeast(1)
        val interval = (reply.interval ?: 5).coerceAtLeast(1)
        deviceCode = code
        expiry = clock.nowMillis() + seconds * 1000
        intervalMillis = interval * 1000L
        nextPoll = clock.nowMillis() + intervalMillis
        AuthorizationStep.DeviceCode(url, user, expiry, interval)
    }

    override suspend fun continueAuthorization(): AuthorizationStep = lock.withLock {
        if (cancelled) return@withLock AuthorizationStep.Denied
        if (reauth) return@withLock AuthorizationStep.ReauthRequired
        if (access != null) return@withLock AuthorizationStep.Ready
        val code = deviceCode ?: return@withLock AuthorizationStep.Denied
        val now = clock.nowMillis()
        if (now >= expiry) return@withLock AuthorizationStep.Expired
        if (now < nextPoll) return@withLock AuthorizationStep.Pending
        nextPoll = now + intervalMillis
        val reply = http.post(endpoints.tokenUrl, mapOf(
            "client_id" to clientId,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "device_code" to code
        ))
        when (reply.error) {
            "authorization_pending" -> AuthorizationStep.Pending
            "slow_down" -> {
                intervalMillis += 5000L
                nextPoll = now + intervalMillis
                AuthorizationStep.Pending
            }
            "access_denied" -> { deviceCode = null; AuthorizationStep.Denied }
            "expired_token" -> { deviceCode = null; AuthorizationStep.Expired }
            null -> {
                val refresh = reply.refreshToken ?: return@withLock AuthorizationStep.Denied
                val bearer = reply.accessToken ?: return@withLock AuthorizationStep.Denied
                tokens.save(refresh)
                access = SourceAccessToken(bearer)
                accessExpiry = now + (reply.expiresIn ?: 3600).coerceAtLeast(1) * 1000
                deviceCode = null
                AuthorizationStep.Ready
            }
            else -> AuthorizationStep.Denied
        }
    }

    override suspend fun accessToken(): SourceAccessToken? = lock.withLock {
        if (cancelled || reauth) return@withLock null
        if (access != null && clock.nowMillis() + 30_000 < accessExpiry) return@withLock access
        val refresh = tokens.load() ?: return@withLock null
        val reply = http.post(endpoints.tokenUrl, mapOf(
            "client_id" to clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to refresh
        ))
        if (reply.error == "invalid_grant") {
            reauth = true
            access = null
            return@withLock null
        }
        if (reply.error != null || reply.accessToken == null) return@withLock null
        reply.refreshToken?.let { tokens.save(it) }
        access = SourceAccessToken(reply.accessToken)
        accessExpiry = clock.nowMillis() + (reply.expiresIn ?: 3600).coerceAtLeast(1) * 1000
        access
    }

    suspend fun cancel() = lock.withLock {
        cancelled = true
        deviceCode = null
        access = null
    }

    override suspend fun revoke(): Boolean = lock.withLock {
        val refresh = tokens.load()
        val revoked = if (refresh == null) true else runCatching {
            http.post(endpoints.revokeUrl, mapOf("token" to refresh)).error == null
        }.getOrDefault(false)
        tokens.save(null)
        access = null
        deviceCode = null
        cancelled = true
        revoked
    }
}
