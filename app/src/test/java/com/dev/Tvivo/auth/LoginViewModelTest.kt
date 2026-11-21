package com.dev.Tvivo.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.data.model.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    private class FakeCredentialsStore(var value: Credentials? = null) : LoginCredentialStore {
        override suspend fun load() = value
        override suspend fun save(value: Credentials) { this.value = value }
        override suspend fun wipe() { value = null }
    }

    @Test fun sourceStoreFailureDoesNotAuthenticateAndRestoresLegacyCredentials() = runTest(dispatcher) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prior = Credentials("old.example", 8080, "old-user", "old-password")
        val credentialsStore = FakeCredentialsStore(prior)
        val account = AuthenticatedAccount(
            Credentials("new.example", 8080, "new-user", "new-password"),
            "new-account",
            UserInfo("new-user", 1, "Active", null, null, null, null, null, emptyList()),
            null
        )
        val model = LoginViewModel(
            application,
            { Result.success(account) },
            credentialsStore,
            LoginSourceStore { error("forced source-store failure") }
        )

        model.onServerChanged("new.example:8080")
        model.onUsernameChanged("new-user")
        model.onPasswordChanged("new-password")
        model.submit()
        advanceUntilIdle()

        assertNull(model.state.value.authenticated)
        assertEquals("new-password", model.state.value.password)
        assertEquals("Could not save this account. Please try again.", model.state.value.errorMessageOverride)
        assertEquals(prior, credentialsStore.value)
        assertTrue(!model.state.value.isSubmitting)
    }
}
