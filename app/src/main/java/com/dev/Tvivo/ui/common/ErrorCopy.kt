package com.dev.Tvivo.ui.common

import com.dev.Tvivo.data.AppError
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One taxonomy, one place that turns it into words and an action.
 *
 * Each error carries its own action: a Retry button on an error that retrying cannot
 * fix teaches the user the app is broken.
 */
data class ErrorPresentation(
    val message: String,
    val primaryAction: String?,
    val secondaryAction: String? = null
)

object ErrorCopy {

    fun of(error: AppError): ErrorPresentation = when (error) {
        AppError.Unreachable -> ErrorPresentation(
            message = "Can't reach that server. Check the address and port.",
            primaryAction = "Retry",
            secondaryAction = "Edit server"
        )

        AppError.AuthFailed -> ErrorPresentation(
            message = "That username or password was rejected.",
            primaryAction = "Edit credentials"
        )

        is AppError.AccountExpired -> ErrorPresentation(
            message = error.expiresAt
                ?.let { "This account expired on ${formatDate(it)}." }
                ?: "This account is no longer active.",
            primaryAction = "Dismiss"
        )

        AppError.ConnectionLimitReached -> ErrorPresentation(
            message = "Another device may be using this account.",
            primaryAction = "Retry"
        )

        AppError.StreamUnavailable -> ErrorPresentation(
            message = "This stream isn't available right now.",
            primaryAction = "Back to list"
        )

        AppError.Timeout -> ErrorPresentation(
            message = "The server took too long to answer.",
            primaryAction = "Retry"
        )

        AppError.Empty -> ErrorPresentation(
            message = "Nothing in this category",
            primaryAction = "Refresh"
        )
    }

    /**
     * D-2 — the **login** rendering of the same taxonomy, with a hard one-line budget.
     *
     * On the login screen a second line pushes the action row down into the TV keyboard,
     * which is the Q-11 failure. The screen has no room to absorb wrapping, so copy that
     * does not fit is **rewritten shorter**, never wrapped: [LOGIN_LINE_BUDGET] is the
     * width of the 820 px form column at `body`, and `ErrorCopyTest` holds every message
     * to it so a future edit cannot quietly reintroduce the second line.
     *
     * Only `Unreachable` needs shortening. It loses "Check the address and port." —
     * which is no loss, because the `Edit server` action says the same thing and is the
     * thing the user has to press anyway.
     */
    fun forLogin(error: AppError): ErrorPresentation = when (error) {
        AppError.Unreachable -> ErrorPresentation(
            message = "Can't reach that server.",
            primaryAction = "Retry",
            secondaryAction = "Edit server"
        )
        else -> of(error)
    }

    /** Characters that fit one line of `body` in the 820 px login column. */
    const val LOGIN_LINE_BUDGET = 48

    private fun formatDate(epochSeconds: Long): String =
        SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000))
}
