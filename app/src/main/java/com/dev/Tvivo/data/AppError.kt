package com.dev.Tvivo.data

sealed class AppError {
    /** Host did not resolve, refused the connection, or the network is down. */
    data object Unreachable : AppError()

    /** Panel answered, credentials rejected (`auth: 0`). */
    data object AuthFailed : AppError()

    /** Panel answered, account is not `Active`. [expiresAt] is the raw `exp_date` epoch seconds. */
    data class AccountExpired(val expiresAt: Long?) : AppError()

    /**
     * Probable, never definitive: panels signal an exhausted slot inconsistently
     * (HTTP error, HTML body, malformed media, or simply terminating playback).
     */
    data object ConnectionLimitReached : AppError()

    data object StreamUnavailable : AppError()

    data object Timeout : AppError()

    /** Request succeeded and returned nothing. An empty-state visual, not an error visual. */
    data object Empty : AppError()
}
