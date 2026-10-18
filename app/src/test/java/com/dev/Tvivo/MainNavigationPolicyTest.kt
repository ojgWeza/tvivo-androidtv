package com.dev.Tvivo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationPolicyTest {
    @Test fun seeAllBackReturnsToSearchOnlyWhenSearchOpenedIt() {
        assertTrue(shouldReturnToSearchAfterSeeAll(true, false))
        assertFalse(shouldReturnToSearchAfterSeeAll(false, false))
        assertFalse(shouldReturnToSearchAfterSeeAll(true, true))
    }
}
