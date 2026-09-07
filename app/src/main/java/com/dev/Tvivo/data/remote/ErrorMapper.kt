package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.data.model.UserInfo
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** HTTP/IO failures map to the taxonomy once, here, and nowhere else. */
object ErrorMapper {

    fun fromThrowable(t: Throwable): AppError = when (t) {
        is SocketTimeoutException -> AppError.Timeout
        is UnknownHostException, is ConnectException -> AppError.Unreachable
        is IOException -> AppError.Unreachable
        else -> AppError.Unreachable
    }

    fun fromHttpCode(code: Int): AppError = when (code) {
        401, 403 -> AppError.AuthFailed
        404 -> AppError.StreamUnavailable
        408 -> AppError.Timeout
        // Panels commonly answer an exhausted connection slot with a 5xx or an HTML body.
        in 500..599 -> AppError.ConnectionLimitReached
        else -> AppError.Unreachable
    }

    /**
     * A panel answers a bad password with HTTP 200 and `auth: 0`, so auth outcome is a
     * body concern, not a status-code concern. Returns null when the account is usable.
     */
    fun fromUserInfo(info: UserInfo?): AppError? = when {
        info == null -> AppError.AuthFailed
        !info.isAuthenticated -> AppError.AuthFailed
        !info.isActive -> AppError.AccountExpired(info.expiresAtEpochSeconds)
        else -> null
    }
}
