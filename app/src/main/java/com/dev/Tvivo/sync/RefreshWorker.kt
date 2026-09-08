package com.dev.Tvivo.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.data.repository.LiveRepository
import com.dev.Tvivo.data.repository.VodRepository
import java.util.concurrent.TimeUnit

/**
 * Phase 5. Re-syncs the catalog in the background so the first launch of the day does not
 * pay for it in the foreground.
 *
 * The cache TTL is 24 h. Without this, every morning's first open of a listing finds
 * everything stale and re-downloads ~15 MB while the user waits — which is exactly the
 * cost Q-13 was about, only moved from "every listing open" to "once a day".
 *
 * **It refreshes on the TTL, it does not force.** `force = false` throughout, so the work
 * is a no-op if something already refreshed inside the window. Forcing here would undo
 * the `isFresh` gate that Q-13 added and re-download the catalog on a timer.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = CredentialsStore(applicationContext)
        // Signed out is a success, not a failure: there is nothing to refresh and
        // retrying with backoff would never resolve it.
        val credentials = store.load() ?: return Result.success()
        DiagnosticLog.info("refresh", "Scheduled catalog refresh started")

        val db = AppDatabase.get(applicationContext)
        val accountId = AccountIdentity.of(credentials)

        return runCatching {
            VodRepository(db, credentials, accountId).refreshCategories(force = false)
            LiveRepository(db, credentials, accountId).refreshCategories(force = false)

            val syncer = CatalogSyncer(db, credentials, accountId)
            syncer.syncVod(force = false)
            syncer.syncLive(force = false)
            syncer.syncSeries(force = false)
        }.fold(
            onSuccess = {
                DiagnosticLog.info("refresh", "Scheduled catalog refresh finished")
                Result.success()
            },
            // `retry`, not `failure`: the usual reason this fails is that the panel or the
            // network was unreachable overnight, which is precisely what backoff is for.
            onFailure = { t ->
                DiagnosticLog.warn(
                    "refresh",
                    "Scheduled refresh could not reach the panel; will retry " +
                        "(${t::class.simpleName})"
                )
                Result.retry()
            }
        )
    }

    companion object {
        private const val WORK_NAME = "tvivo-catalog-refresh"

        /**
         * Idempotent — safe to call on every launch. `KEEP` means an already-scheduled
         * chain is left alone rather than being torn down and rebuilt, which would reset
         * its interval every time the app opened and, on a TV that is opened several
         * times an evening, mean it never actually ran.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        // The sync is tens of MB. Metered-network policy is the user's,
                        // not this app's, and CONNECTED is what a TV on Wi-Fi wants.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                // 12 h against a 24 h TTL: two chances to land inside each window, so a
                // single missed run does not push the user back into a foreground sync.
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
