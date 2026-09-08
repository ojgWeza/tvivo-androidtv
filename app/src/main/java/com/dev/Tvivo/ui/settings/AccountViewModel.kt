package com.dev.Tvivo.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.AppErrorException
import com.dev.Tvivo.auth.AuthRepository
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.repository.LiveRepository
import com.dev.Tvivo.data.repository.VodRepository
import com.dev.Tvivo.sync.CatalogSyncer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AccountUiState(
    val isLoading: Boolean = true,
    val username: String? = null,
    /** `host:port`. The password is never surfaced, not even masked. */
    val server: String? = null,
    val status: String? = null,
    val expiry: String? = null,
    val connections: String? = null,
    val isTrial: Boolean = false,
    /** True inside the last 7 days, so the strip can say so before it stops working. */
    val expiringSoon: Boolean = false,
    val error: AppError? = null,
    val isRefreshing: Boolean = false,
    val refreshMessage: String? = null,
    val signedOut: Boolean = false
) {
    /** One line for the Home strip: enough to answer "which account, until when". */
    val summary: String?
        get() = when {
            username == null -> null
            expiry == null -> username
            else -> "$username · $expiry"
        }
}

/**
 * Account state and the whole-app actions that belong to it rather than to any one
 * content type: sign out, switch account, refresh everything, exit.
 *
 * The expiry, status and connection count are **re-fetched live** rather than cached
 * from the login response — a subscription that lapsed yesterday should say so today,
 * and this is one fast call.
 */
class AccountViewModel(application: Application) : AndroidViewModel(application) {

    private val store = CredentialsStore(application)
    private val auth = AuthRepository()
    private val db = AppDatabase.get(application)

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val credentials = store.load()
            if (credentials == null) {
                _state.update { it.copy(isLoading = false, signedOut = true) }
                return@launch
            }
            // Clearing `signedOut` here matters: it is what lets the screen be
            // re-entered after signing back in, instead of immediately bouncing to
            // Login off a stale flag.
            _state.update {
                it.copy(
                    isLoading = true,
                    signedOut = false,
                    username = credentials.username,
                    server = credentials.hostAndPort()
                )
            }

            auth.authenticate(credentials)
                .onSuccess { account ->
                    val info = account.userInfo
                    val expiresAt = info.expiresAtEpochSeconds
                    _state.update {
                        it.copy(
                            isLoading = false,
                            username = info.username ?: credentials.username,
                            status = info.status,
                            expiry = formatExpiry(expiresAt),
                            expiringSoon = isExpiringSoon(expiresAt),
                            isTrial = info.isTrial == "1",
                            connections = connectionsText(
                                info.activeConnections,
                                info.maxConnections
                            ),
                            error = null
                        )
                    }
                }
                .onFailure { t ->
                    // Offline is not signed-out: keep showing who is signed in, and say
                    // only that the live detail could not be refreshed.
                    _state.update {
                        it.copy(isLoading = false, error = t.toAppError())
                    }
                }
        }
    }

    /**
     * Forces every cached list to re-fetch, TTL ignored, for both content types. This is
     * the global counterpart to the per-category Refresh in the browse header.
     */
    fun refreshEverything() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true, refreshMessage = null) }
        viewModelScope.launch {
            val credentials = store.load()
            if (credentials == null) {
                _state.update { it.copy(isRefreshing = false, signedOut = true) }
                return@launch
            }
            val accountId = AccountIdentity.of(credentials)
            val vod = VodRepository(db, credentials, accountId)
            val live = LiveRepository(db, credentials, accountId)
            val syncer = CatalogSyncer(db, credentials, accountId)

            val categories = listOf(
                vod.refreshCategories(force = true),
                live.refreshCategories(force = true)
            )
            // The catalogs are the slow part and the reason this is a screen action
            // rather than something that happens on every launch.
            val vodRows = syncer.syncVod().getOrDefault(0)
            val liveRows = syncer.syncLive().getOrDefault(0)

            val failed = categories.any { it.isFailure }
            _state.update {
                it.copy(
                    isRefreshing = false,
                    refreshMessage = if (failed) {
                        "Could not reach the panel"
                    } else {
                        "Refreshed $vodRows movies and $liveRows channels"
                    }
                )
            }
            load()
        }
    }

    /**
     * Wipes the stored credentials and the Tink keyset. The cached catalog is left
     * alone: it is account-scoped, so signing back into the same account finds it warm,
     * and signing into a different one cannot see it.
     */
    fun signOut() {
        viewModelScope.launch {
            store.wipe()
            _state.update { AccountUiState(isLoading = false, signedOut = true) }
        }
    }

    fun dismissRefreshMessage() = _state.update { it.copy(refreshMessage = null) }

    private fun connectionsText(active: String?, max: String?): String? {
        if (max == null) return null
        return "${active ?: "0"} of $max in use"
    }

    private fun formatExpiry(epochSeconds: Long?): String? {
        // Panels use null/0 for "never expires" rather than a far-future date.
        if (epochSeconds == null || epochSeconds <= 0L) return "No expiry"
        val date = Date(TimeUnit.SECONDS.toMillis(epochSeconds))
        val formatted = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(date)
        val daysLeft = daysUntil(epochSeconds)
        return when {
            daysLeft < 0 -> "Expired $formatted"
            daysLeft == 0L -> "Expires today"
            daysLeft == 1L -> "Expires tomorrow ($formatted)"
            daysLeft <= EXPIRY_WARNING_DAYS -> "Expires in $daysLeft days ($formatted)"
            else -> "Expires $formatted"
        }
    }

    private fun isExpiringSoon(epochSeconds: Long?): Boolean {
        if (epochSeconds == null || epochSeconds <= 0L) return false
        return daysUntil(epochSeconds) <= EXPIRY_WARNING_DAYS
    }

    private fun daysUntil(epochSeconds: Long): Long =
        TimeUnit.MILLISECONDS.toDays(
            TimeUnit.SECONDS.toMillis(epochSeconds) - System.currentTimeMillis()
        )

    private fun Throwable.toAppError(): AppError =
        (this as? AppErrorException)?.error ?: AppError.Unreachable

    private companion object {
        const val EXPIRY_WARNING_DAYS = 7L
    }
}
