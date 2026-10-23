package com.dev.Tvivo.auth

import com.dev.Tvivo.BuildConfig
import com.dev.Tvivo.source.AuthorizationStep
import com.dev.Tvivo.source.SourceAccessToken
import com.dev.Tvivo.source.SourceAuthorization
import com.dev.Tvivo.source.drive.AuthClock
import com.dev.Tvivo.source.drive.DeviceCodeAuthorizer
import com.dev.Tvivo.source.drive.DeviceCodeEndpoints
import com.dev.Tvivo.source.drive.DeviceCodeHttp
import com.dev.Tvivo.source.drive.OAuthReply
import com.dev.Tvivo.source.drive.RefreshTokenStore
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Injectable consent boundary. A later Play-services binding owns the resolution Intent. */
interface PhoneAuthorizationClient {
    sealed interface Result {
        data class Token(val value: String) : Result
        data class Consent(val requestId: String) : Result
        data object Denied : Result
    }
    suspend fun authorize(): Result
    suspend fun continueConsent(requestId: String): Result
    suspend fun revokeAccess(): Boolean
}

class PhoneSourceAuthorization(private val client: PhoneAuthorizationClient) : SourceAuthorization {
    private var requestId: String? = null
    private var token: SourceAccessToken? = null

    private fun step(result: PhoneAuthorizationClient.Result): AuthorizationStep = when (result) {
        is PhoneAuthorizationClient.Result.Token -> {
            token = SourceAccessToken(result.value)
            requestId = null
            AuthorizationStep.Ready
        }
        is PhoneAuthorizationClient.Result.Consent -> {
            requestId = result.requestId
            AuthorizationStep.PhoneConsent(result.requestId)
        }
        PhoneAuthorizationClient.Result.Denied -> {
            requestId = null
            AuthorizationStep.Denied
        }
    }

    override suspend fun begin() = step(client.authorize())
    override suspend fun continueAuthorization(): AuthorizationStep = requestId?.let {
        step(client.continueConsent(it))
    } ?: AuthorizationStep.Denied
    override suspend fun accessToken(): SourceAccessToken? {
        if (token == null) step(client.authorize())
        return token
    }
    /** Called after a Drive 401; the next request must run authorize again. */
    fun invalidateAccessToken() { token = null }
    override suspend fun revoke(): Boolean {
        token = null
        requestId = null
        return client.revokeAccess()
    }
}

/** Test and mock adapter; never contacts Google or opens consent UI. */
class FakeSourceAuthorization(
    private val steps: ArrayDeque<AuthorizationStep>,
    private val bearer: SourceAccessToken = SourceAccessToken("mock-bearer")
) : SourceAuthorization {
    private var current: AuthorizationStep = AuthorizationStep.Pending
    override suspend fun begin(): AuthorizationStep = advance()
    override suspend fun continueAuthorization(): AuthorizationStep = advance()
    private fun advance(): AuthorizationStep {
        current = steps.removeFirstOrNull() ?: current
        return current
    }
    override suspend fun accessToken(): SourceAccessToken? =
        if (current == AuthorizationStep.Ready) bearer else null
    override suspend fun revoke(): Boolean { current = AuthorizationStep.Denied; return true }
}

class DeviceCodeSourceAuthorization(
    clientId: String = BuildConfig.DRIVE_TV_CLIENT_ID,
    http: DeviceCodeHttp,
    tokens: RefreshTokenStore,
    clock: AuthClock = AuthClock(System::currentTimeMillis),
    endpoints: DeviceCodeEndpoints = DeviceCodeEndpoints()
) : SourceAuthorization {
    private val machine = DeviceCodeAuthorizer(clientId, http, tokens, clock, endpoints)
    override suspend fun begin() = machine.begin()
    override suspend fun continueAuthorization() = machine.continueAuthorization()
    override suspend fun accessToken() = machine.accessToken()
    override suspend fun revoke() = machine.revoke()
    suspend fun cancel() = machine.cancel()
}

/** Google defaults; mock URLs may be supplied by debug-only callers. */
class OkHttpDeviceCodeHttp(private val client: OkHttpClient = OkHttpClient()) : DeviceCodeHttp {
    override suspend fun post(url: String, form: Map<String, String>): OAuthReply = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (key, value) -> add(key, value) } }.build()
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) return@use OAuthReply(error = if (response.isSuccessful) null else "http_${response.code}")
            val json = JsonParser.parseString(text).asJsonObject
            fun string(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asString
            fun long(name: String) = json.get(name)?.takeUnless { it.isJsonNull }?.asLong
            OAuthReply(
                deviceCode = string("device_code"),
                userCode = string("user_code"),
                verificationUrl = string("verification_url") ?: string("verification_uri"),
                expiresIn = long("expires_in"),
                interval = long("interval")?.toInt(),
                accessToken = string("access_token"),
                refreshToken = string("refresh_token"),
                error = string("error") ?: if (response.isSuccessful) null else "http_${response.code}"
            )
        }
    }
}
