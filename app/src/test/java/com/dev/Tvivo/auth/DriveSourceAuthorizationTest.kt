package com.dev.Tvivo.auth

import com.dev.Tvivo.source.AuthorizationStep
import com.dev.Tvivo.source.drive.AuthClock
import com.dev.Tvivo.source.drive.DeviceCodeEndpoints
import com.dev.Tvivo.source.drive.RefreshTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DriveSourceAuthorizationTest {
    @Test fun phoneConsentAndFreshTokenAfterInvalidation() = runBlocking {
        var authorizations = 0
        val client = object : PhoneAuthorizationClient {
            override suspend fun authorize(): PhoneAuthorizationClient.Result =
                if (++authorizations == 1) PhoneAuthorizationClient.Result.Consent("request-1")
                else PhoneAuthorizationClient.Result.Token("fresh")
            override suspend fun continueConsent(requestId: String) =
                PhoneAuthorizationClient.Result.Token("first")
            override suspend fun revokeAccess() = true
        }
        val auth = PhoneSourceAuthorization(client)
        assertEquals(AuthorizationStep.PhoneConsent("request-1"), auth.begin())
        assertEquals(AuthorizationStep.Ready, auth.continueAuthorization())
        assertEquals("first", auth.accessToken()?.value)
        auth.invalidateAccessToken()
        assertEquals("fresh", auth.accessToken()?.value)
        assertTrue(auth.revoke())
    }

    @Test fun mockBaseUrlUsesInjectedDeviceAndTokenEndpoints() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_code":"mock-device","user_code":"TV-0042","verification_url":"http://mock/verify","expires_in":60,"interval":1}"""))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"mock-access","refresh_token":"mock-refresh","expires_in":3600}"""))
            var now = 0L
            var refresh: String? = null
            val tokens = object : RefreshTokenStore {
                override suspend fun load() = refresh
                override suspend fun save(token: String?) { refresh = token }
            }
            val base = server.url("/").toString().removeSuffix("/")
            val auth = DeviceCodeSourceAuthorization(
                "mock-client", OkHttpDeviceCodeHttp(), tokens, AuthClock { now },
                DeviceCodeEndpoints("$base/device/code", "$base/token", "$base/token")
            )
            assertEquals("TV-0042", (auth.begin() as AuthorizationStep.DeviceCode).userCode)
            now = 1000
            assertEquals(AuthorizationStep.Pending, auth.continueAuthorization())
            now = 2000
            assertEquals(AuthorizationStep.Ready, auth.continueAuthorization())
            assertEquals("mock-refresh", refresh)
            assertEquals("mock-access", auth.accessToken()?.value)
            assertEquals("/device/code", server.takeRequest().path)
            assertEquals("/token", server.takeRequest().path)
            assertEquals("/token", server.takeRequest().path)
        }
    }

    @Test fun fakeAuthorizationHonorsReadyAndRevoke() = runBlocking {
        val fake = FakeSourceAuthorization(ArrayDeque(listOf(AuthorizationStep.Pending, AuthorizationStep.Ready)))
        assertEquals(AuthorizationStep.Pending, fake.begin())
        assertNull(fake.accessToken())
        assertEquals(AuthorizationStep.Ready, fake.continueAuthorization())
        assertEquals("mock-bearer", fake.accessToken()?.value)
        assertTrue(fake.revoke())
        assertNull(fake.accessToken())
    }
}
