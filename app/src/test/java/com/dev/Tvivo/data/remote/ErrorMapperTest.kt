package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.data.model.UserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorMapperTest {

    private fun userInfo(
        auth: Int? = 1,
        status: String? = "Active",
        expDate: String? = "1808067679"
    ) = UserInfo(
        username = "u",
        auth = auth,
        status = status,
        expDate = expDate,
        isTrial = "0",
        activeConnections = "0",
        createdAt = "0",
        maxConnections = "1",
        allowedOutputFormats = listOf("ts")
    )

    @Test
    fun `unknown host is unreachable, not a timeout`() {
        assertEquals(AppError.Unreachable, ErrorMapper.fromThrowable(UnknownHostException()))
        assertEquals(AppError.Unreachable, ErrorMapper.fromThrowable(ConnectException()))
        assertEquals(AppError.Unreachable, ErrorMapper.fromThrowable(IOException()))
    }

    @Test
    fun `socket timeout maps to timeout so the user gets a retry that can work`() {
        assertEquals(AppError.Timeout, ErrorMapper.fromThrowable(SocketTimeoutException()))
    }

    @Test
    fun `401 and 403 are auth failures`() {
        assertEquals(AppError.AuthFailed, ErrorMapper.fromHttpCode(401))
        assertEquals(AppError.AuthFailed, ErrorMapper.fromHttpCode(403))
    }

    @Test
    fun `404 is a missing stream`() {
        assertEquals(AppError.StreamUnavailable, ErrorMapper.fromHttpCode(404))
    }

    @Test
    fun `5xx is treated as a probable connection limit`() {
        // max_connections is 1 on this account, and panels signal an exhausted slot
        // inconsistently — this classification is probable, never definitive.
        assertEquals(AppError.ConnectionLimitReached, ErrorMapper.fromHttpCode(500))
        assertEquals(AppError.ConnectionLimitReached, ErrorMapper.fromHttpCode(503))
    }

    /** A wrong password comes back as HTTP 200 with `auth: 0`. */
    @Test
    fun `auth zero is an auth failure despite a 200`() {
        assertEquals(AppError.AuthFailed, ErrorMapper.fromUserInfo(userInfo(auth = 0)))
    }

    @Test
    fun `missing user_info is an auth failure`() {
        assertEquals(AppError.AuthFailed, ErrorMapper.fromUserInfo(null))
    }

    @Test
    fun `non-active status is expiry and carries the real exp_date`() {
        val error = ErrorMapper.fromUserInfo(userInfo(status = "Expired"))
        assertEquals(AppError.AccountExpired(1808067679L), error)
    }

    @Test
    fun `expired account with an unparseable exp_date still reports expiry`() {
        val error = ErrorMapper.fromUserInfo(userInfo(status = "Banned", expDate = null))
        assertEquals(AppError.AccountExpired(null), error)
    }

    @Test
    fun `an active authenticated account is not an error`() {
        assertNull(ErrorMapper.fromUserInfo(userInfo()))
    }

    @Test
    fun `status casing from the panel is not trusted to be exact`() {
        assertNull(ErrorMapper.fromUserInfo(userInfo(status = "active")))
    }
}
