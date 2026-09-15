package com.dev.Tvivo.ui.common

import com.dev.Tvivo.data.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-2. The login screen's error line has a hard one-line budget: a second line pushes the
 * action row down toward the TV keyboard, which is the Q-11 failure this design pass
 * exists to close. The budget is a layout constraint, so it is asserted rather than left
 * to whoever next edits the copy.
 */
class ErrorCopyTest {

    private val allErrors = listOf(
        AppError.Unreachable,
        AppError.AuthFailed,
        AppError.AccountExpired(expiresAt = null),
        AppError.AccountExpired(expiresAt = 1_772_000_000L),
        AppError.ConnectionLimitReached,
        AppError.StreamUnavailable,
        AppError.Timeout,
        AppError.Empty
    )

    @Test
    fun `every login message fits one line`() {
        for (error in allErrors) {
            val message = ErrorCopy.forLogin(error).message
            assertTrue(
                "$error: \"$message\" is ${message.length} chars, " +
                    "over the ${ErrorCopy.LOGIN_LINE_BUDGET} budget. Rewrite it shorter — " +
                    "do not let it wrap.",
                message.length <= ErrorCopy.LOGIN_LINE_BUDGET
            )
        }
    }

    @Test
    fun `login shortens Unreachable and keeps its actions`() {
        val full = ErrorCopy.of(AppError.Unreachable)
        val login = ErrorCopy.forLogin(AppError.Unreachable)

        // The full-screen renderer has room for the extra sentence and keeps it.
        assertTrue(full.message.length > ErrorCopy.LOGIN_LINE_BUDGET)
        assertTrue(login.message.length <= ErrorCopy.LOGIN_LINE_BUDGET)

        // What is dropped is the sentence, never the way out: `Edit server` says the same
        // thing as "Check the address and port" and is the control the user must press.
        assertEquals(full.primaryAction, login.primaryAction)
        assertEquals(full.secondaryAction, login.secondaryAction)
    }

    @Test
    fun `login leaves every other error untouched`() {
        for (error in allErrors.filter { it != AppError.Unreachable }) {
            assertEquals(ErrorCopy.of(error), ErrorCopy.forLogin(error))
        }
    }

    @Test
    fun `an expired account names the real date`() {
        val copy = ErrorCopy.of(AppError.AccountExpired(expiresAt = 1_772_000_000L))
        // The point is that it reports `exp_date` rather than saying something vague;
        // the exact rendering follows the device locale, so only the year is asserted.
        assertTrue(copy.message, copy.message.contains("2026"))
        assertEquals("Dismiss", copy.primaryAction)
    }
}
