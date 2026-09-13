package com.dev.Tvivo.data

sealed class AppError {
    data object Unreachable : AppError()
    data object AuthFailed : AppError()
    data class AccountExpired(val expiresAt: Long?) : AppError()
    data object ConnectionLimitReached : AppError()
    data object StreamUnavailable : AppError()
    data object Timeout : AppError()
    data object Empty : AppError()
}
