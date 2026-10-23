package com.dev.Tvivo.source.drive

import com.dev.Tvivo.source.AuthorizationStep
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceCodeAuthorizerTest {
    private class Clock(var now: Long = 0) : AuthClock { override fun nowMillis() = now }
    private class Tokens(var value: String? = null) : RefreshTokenStore {
        val writes = mutableListOf<String?>()
        override suspend fun load() = value
        override suspend fun save(token: String?) { value = token; writes += token }
    }
    private class Http : DeviceCodeHttp {
        val replies = ArrayDeque<OAuthReply>()
        val forms = mutableListOf<Map<String, String>>()
        override suspend fun post(url: String, form: Map<String, String>): OAuthReply {
            forms += form
            return replies.removeFirst()
        }
    }
    private fun code() = OAuthReply("device", "CASE-SENSITIVE", "https://example.test/verify", 60, 2)
    private fun ready(refresh: String = "refresh") = OAuthReply(accessToken = "access", refreshToken = refresh, expiresIn = 60)
    private fun machine(http: Http, tokens: Tokens, clock: Clock) =
        DeviceCodeAuthorizer("local-client", http, tokens, clock, DeviceCodeEndpoints("/device/code", "/token", "/revoke"))

    @Test fun requestCodePendingSlowDownAndSuccess() = runBlocking {
        val clock = Clock(); val http = Http(); val tokens = Tokens()
        http.replies.addAll(listOf(code(), OAuthReply(error = "authorization_pending"), OAuthReply(error = "slow_down"), ready()))
        val auth = machine(http, tokens, clock)
        val first = auth.begin() as AuthorizationStep.DeviceCode
        assertEquals("CASE-SENSITIVE", first.userCode)
        assertEquals(2, first.pollIntervalSeconds)
        assertEquals(AuthorizationStep.Pending, auth.continueAuthorization())
        assertEquals(1, http.forms.size)
        clock.now = 2000
        assertEquals(AuthorizationStep.Pending, auth.continueAuthorization())
        clock.now = 4000
        assertEquals(AuthorizationStep.Pending, auth.continueAuthorization())
        clock.now = 10_999
        assertEquals(AuthorizationStep.Pending, auth.continueAuthorization())
        clock.now = 11_000
        assertEquals(AuthorizationStep.Ready, auth.continueAuthorization())
        assertEquals("refresh", tokens.value)
        assertEquals("access", auth.accessToken()?.value)
    }

    @Test fun denialExpiryAndCancelNeverPollAgain() = runBlocking {
        val deniedHttp = Http().apply { replies.addAll(listOf(code(), OAuthReply(error = "access_denied"))) }
        val clock = Clock(); val denied = machine(deniedHttp, Tokens(), clock)
        denied.begin(); clock.now = 2000
        assertEquals(AuthorizationStep.Denied, denied.continueAuthorization())
        assertEquals(AuthorizationStep.Denied, denied.continueAuthorization())
        val expiredHttp = Http().apply { replies.add(code()) }
        val expired = machine(expiredHttp, Tokens(), Clock(0))
        expired.begin(); expired.cancel()
        assertEquals(AuthorizationStep.Denied, expired.continueAuthorization())
        val expiryClock = Clock(); val expiryHttp = Http().apply { replies.add(code()) }
        val expiry = machine(expiryHttp, Tokens(), expiryClock)
        expiry.begin(); expiryClock.now = 60_000
        assertEquals(AuthorizationStep.Expired, expiry.continueAuthorization())
        assertEquals(1, expiryHttp.forms.size)
    }

    @Test fun refreshRotationSerializedInvalidGrantAndOfflineRevoke() = runBlocking {
        val clock = Clock(); val tokens = Tokens("old"); val http = Http()
        http.replies.addAll(listOf(
            OAuthReply(accessToken = "new-access", refreshToken = "rotated", expiresIn = 60),
            OAuthReply(error = "invalid_grant")
        ))
        val auth = machine(http, tokens, clock)
        val a = async { auth.accessToken()?.value }
        val b = async { auth.accessToken()?.value }
        assertEquals("new-access", a.await())
        assertEquals("new-access", b.await())
        assertEquals("rotated", tokens.value)
        assertEquals(1, http.forms.size)
        clock.now = 31_000
        assertNull(auth.accessToken())
        assertEquals(AuthorizationStep.ReauthRequired, auth.continueAuthorization())
        assertEquals(2, http.forms.size)
        assertFalse(auth.revoke()) // no response queued: local grant still removed
        assertNull(tokens.value)
    }
}
