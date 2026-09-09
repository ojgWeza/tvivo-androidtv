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
     * On success the port is corrected from `server_info` — the login screen only asks
     * for one server field, so a user who typed a bare host gets the real port here.
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

                val reportedPort = body?.serverInfo?.port?.toIntOrNull()
                val corrected = input.copy(port = input.port ?: reportedPort)

                Result.success(
                    AuthenticatedAccount(
                        credentials = corrected,
                        accountId = AccountIdentity.of(corrected),
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
