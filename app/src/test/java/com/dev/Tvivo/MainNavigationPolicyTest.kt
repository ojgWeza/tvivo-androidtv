package com.dev.Tvivo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationPolicyTest {
    @Test fun accountSwitchLoginDoesNotOpenImeBeforeBackCanReturnToHome() {
        assertFalse(shouldAutoShowLoginKeyboard(isTelevision = true, switchingAccount = true))
        assertTrue(shouldAutoShowLoginKeyboard(isTelevision = true, switchingAccount = false))
        assertFalse(shouldAutoShowLoginKeyboard(isTelevision = false, switchingAccount = false))
    }
    @Test fun openCategoryAndQuerySurviveShellStateRestoration() {
        val beforeRotation = ShellBrowseState(categoryId = "201", query = "No Length")
        val restored = ShellBrowseState.restore(beforeRotation.savedFields())
        assertEquals("201", restored.categoryId)
        assertEquals("No Length", restored.query)
        assertEquals("No Length", ShellBrowseState.restore(
            restored.copy(categoryId = null).savedFields()).query)
    }
    @Test fun seeAllBackReturnsToSearchOnlyWhenSearchOpenedIt() {
        assertTrue(shouldReturnToSearchAfterSeeAll(true, false))
        assertFalse(shouldReturnToSearchAfterSeeAll(false, false))
        assertFalse(shouldReturnToSearchAfterSeeAll(true, true))
    }
}
