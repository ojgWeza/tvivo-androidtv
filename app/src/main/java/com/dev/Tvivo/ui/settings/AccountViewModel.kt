package com.dev.Tvivo.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.AppErrorException
import com.dev.Tvivo.auth.AuthRepository
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.auth.SourceStore
import com.dev.Tvivo.data.AppError
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.diagnostics.DiagnosticLog
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
    val lastRefreshedAt: Long? = null,
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
 * Account state and the whole-app actions: sign out, switch account and refresh everything.
 *
 * The expiry, status and connection count are **re-fetched live** rather than cached
 * from the login response — a subscription that lapsed yesterday should say so today,
 * and this is one fast call.
 */
class AccountViewModel(application: Application) : AndroidViewModel(application) {

    private val store = CredentialsStore(application)
    private val auth = AuthRepository()
    private val db = AppDatabase.get(application)
    private val refreshTimes = application.getSharedPreferences("catalog_refresh_times", android.content.Context.MODE_PRIVATE)

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
            val accountId = AccountIdentity.of(credentials)
            val lastCatalogUpdate = listOf(TYPE_VOD, TYPE_SERIES, TYPE_LIVE)
                .mapNotNull { type -> db.catalogSyncDao().get(accountId, type) }
                .filter { it.state == "complete" }
                .maxOfOrNull { it.updatedAt }
            val hasStoredRefresh = refreshTimes.contains("last_$accountId")
            val lastCompletedRefresh = refreshTimes.getLong("last_$accountId", 0L)
                .takeIf { it > 0L }
            // Clearing `signedOut` here matters: it is what lets the screen be
            // re-entered after signing back in, instead of immediately bouncing to
            // Login off a stale flag.
            _state.update {
                it.copy(
                    isLoading = true,
                    signedOut = false,
                    username = credentials.username,
                    server = credentials.hostAndPort(),
                    lastRefreshedAt = if (hasStoredRefresh) lastCompletedRefresh
                        else it.lastRefreshedAt ?: lastCatalogUpdate
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
            if (!refreshTimes.contains("last_$accountId"))
                refreshTimes.edit().putLong("last_$accountId", _state.value.lastRefreshedAt ?: 0L).apply()
            val vod = VodRepository(db, credentials, accountId)
            val live = LiveRepository(db, credentials, accountId)
            val syncer = CatalogSyncer(db, credentials, accountId)

            val categories = listOf(
                vod.refreshCategories(force = true),
                live.refreshCategories(force = true)
            )
            // The catalogs are the slow part and the reason this is a screen action
            // rather than something that happens on every launch.
            // force = true: this button exists precisely to ignore the TTL. It is also
            // now the only way to re-sync inside the 24 h window, so it has to cover
            // every content type — series included, or a stale series catalog would have
            // no manual escape hatch at all.
            val vodResult = syncer.syncVod(force = true)
            val liveResult = syncer.syncLive(force = true)
            val seriesResult = syncer.syncSeries(force = true)
            val vodRows = vodResult.getOrDefault(0)
            val liveRows = liveResult.getOrDefault(0)
            val seriesRows = seriesResult.getOrDefault(0)

            val failed = categories.any { it.isFailure } ||
                listOf(vodResult, liveResult, seriesResult).any { it.isFailure }
            if (failed) {
                DiagnosticLog.warn("refresh", "Manual refresh: categories failed")
            } else {
                DiagnosticLog.info(
                    "refresh",
                    "Manual refresh: $vodRows movies, $liveRows channels, $seriesRows shows"
                )
            }
            val completedAt = if (failed) null else System.currentTimeMillis().also {
                refreshTimes.edit().putLong("last_$accountId", it).apply()
            }
            _state.update {
                val failure = categories.firstOrNull { result -> result.isFailure }?.exceptionOrNull()
                    ?: listOf(vodResult, liveResult, seriesResult)
                        .firstOrNull { result -> result.isFailure }?.exceptionOrNull()
                val copy = AppLanguage.wrap(getApplication<Application>())
                it.copy(
                    isRefreshing = false,
                    refreshMessage = if (failed) {
                        copy.getString(when ((failure as? AppErrorException)?.error) {
                            AppError.RateLimited -> com.dev.Tvivo.R.string.refresh_rate_limited
                            AppError.ServiceUnavailable -> com.dev.Tvivo.R.string.refresh_service_unavailable
                            else -> com.dev.Tvivo.R.string.refresh_unreachable
                        })
                    } else {
                        copy.getString(com.dev.Tvivo.R.string.refresh_result, vodRows, liveRows, seriesRows)
                    },
                    lastRefreshedAt = completedAt ?: it.lastRefreshedAt
                )
            }
            load()
        }
    }

    /**
     * Wipes the stored credentials while retaining the shared Tink keyset. The cached catalog is left
     * alone: it is account-scoped, so signing back into the same account finds it warm,
     * and signing into a different one cannot see it.
     */
    fun signOut() {
        viewModelScope.launch {
            DiagnosticLog.warn("account", "Signed out; stored credentials erased")
            val current = store.load()
            if (current != null) runCatching {
                SourceStore.open(getApplication()).disconnect(AccountIdentity.of(current)) { true }
            }
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
