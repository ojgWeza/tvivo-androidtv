package com.dev.Tvivo.ui.settings

import com.dev.Tvivo.data.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountSignOutStateTest {
    @Test fun failedSignOutKeepsAccountVisibleAndSetsAnError() {
        val prior = AccountUiState(isLoading = true, username = "viewer")

        val failed = signOutFailureState(prior, IllegalStateException("store unavailable"))

        assertEquals("viewer", failed.username)
        assertFalse(failed.signedOut)
        assertFalse(failed.isLoading)
        assertEquals(AppError.Unreachable, failed.error)
    }
}
