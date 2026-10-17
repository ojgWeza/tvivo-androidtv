package com.dev.Tvivo.auth

import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.data.model.UserInfo
import com.dev.Tvivo.data.remote.ErrorMapper
import com.dev.Tvivo.data.remote.XtreamApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AuthenticatedAccount(
    val credentials: Credentials,
    val accountId: String,
    val userInfo: UserInfo,
    val httpsPort: Int?
)

class AuthRepository {

    /**
     * Validates against `player_api.php`. A wrong password comes back as HTTP 200 with
     * `auth: 0`, so the body decides the outcome, not the status code.
     *
     * The typed port is kept as typed: an empty port stays empty (default port) and is
     * never filled in from `server_info`.
     */
    suspend fun authenticate(input: Credentials): Result<AuthenticatedAccount> =
        withContext(Dispatchers.IO) {
            try {
                val response = XtreamApiClient.serviceFor(input)
                    .authenticate(input.username, input.password)

                if (!response.isSuccessful) {
                    DiagnosticLog.error(AREA, "Sign-in rejected, HTTP ${response.code()}")
                    return@withContext Result.failure(
                        AppErrorException(ErrorMapper.fromHttpCode(response.code()))
                    )
                }

                val body = response.body()
                val info = body?.userInfo
                ErrorMapper.fromUserInfo(info)?.let {
                    // The mapped AppError, never the credentials that produced it.
                    DiagnosticLog.error(AREA, "Sign-in refused by panel: $it")
                    return@withContext Result.failure(AppErrorException(it))
                }
                requireNotNull(info)

                // An empty port means the provider's default port. The port a panel reports for
                // itself is often an internal one behind a proxy, so it must never replace that
                // (same rule as the WinUI app, commit 6d73d0c).
                Result.success(
                    AuthenticatedAccount(
                        credentials = input,
                        accountId = AccountIdentity.of(input),
                        userInfo = info,
                        httpsPort = body?.serverInfo?.httpsPort?.toIntOrNull()
                    )
                )
            } catch (t: Throwable) {
                // The exception type only: a network exception's message can echo the
                // request URL, which carries the username and password.
                DiagnosticLog.error(AREA, "Sign-in failed, ${t.javaClass.simpleName}")
                Result.failure(AppErrorException(ErrorMapper.fromThrowable(t)))
            }
        }
}

private const val AREA = "auth"

/** Carries an [AppError] through `Result` without leaking a raw exception to the UI. */
class AppErrorException(val error: AppError) : Exception(error.toString())
