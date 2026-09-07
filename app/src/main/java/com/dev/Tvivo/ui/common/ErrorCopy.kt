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

    private fun formatDate(epochSeconds: Long): String =
        SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000))
}
