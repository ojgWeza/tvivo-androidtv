package com.dev.Tvivo.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.dev.Tvivo.R
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.local.entities.DownloadEntity
import com.dev.Tvivo.data.repository.SeriesRepository
import com.dev.Tvivo.data.repository.VodRepository
import com.dev.Tvivo.download.DownloadController
import com.dev.Tvivo.download.DownloadDependencies
import com.dev.Tvivo.download.DownloadQuota
import com.dev.Tvivo.ui.common.ConfirmDialog
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.launch

internal data class DownloadDisplayRow(val row: DownloadEntity, val title: String, val seriesTitle: String? = null)
internal fun groupDownloads(rows: List<DownloadDisplayRow>): Pair<List<DownloadDisplayRow>, List<DownloadDisplayRow>> =
    rows.partition { it.row.state != "COMPLETED" }

private const val ENABLE_DOWNLOADED_PLAY = true

@Composable
fun DownloadsScreen(credentials: Credentials, accountId: String, onOpenSettings: () -> Unit, onPlayDownloaded: (DownloadEntity, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val vodCatalog = remember(context, credentials, accountId) { VodRepository(com.dev.Tvivo.data.local.AppDatabase.get(context.applicationContext), credentials, accountId) }
    val seriesCatalog = remember(context, credentials, accountId) { SeriesRepository(com.dev.Tvivo.data.local.AppDatabase.get(context.applicationContext), credentials, accountId) }
    val repo = remember(context) { DownloadDependencies.repository(context) }
    val store = remember(context) { DownloadDependencies.preferences(context) }
    val preferences by store.preferences.collectAsStateWithLifecycle(initialValue = com.dev.Tvivo.download.DownloadPreferences())
    val rows by repo.observe(accountId).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var used by remember { mutableLongStateOf(0L) }
    var reserved by remember { mutableLongStateOf(0L) }
    var displayRows by remember { mutableStateOf(emptyList<DownloadDisplayRow>()) }
    var confirmRows by remember { mutableStateOf<List<DownloadEntity>?>(null) }
    var retryBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(rows) {
        used = repo.usedBytes()
        reserved = repo.reservedBytes()
        displayRows = rows.map { row ->
            val id = row.mediaId.toIntOrNull()
            val episode = if (row.kind == "episode") seriesCatalog.episodeById(row.seriesId, row.mediaId) else null
            val title = when (row.kind) {
                "movie" -> id?.let { vodCatalog.byId(it)?.nameDisplay }
                else -> episode?.title
            } ?: context.getString(R.string.download_neutral_title)
            val series = if (row.kind == "episode")
                seriesCatalog.byId(row.seriesId)?.nameDisplay ?: context.getString(R.string.download_neutral_title)
            else null
            DownloadDisplayRow(row, title, series)
        }
    }
    val (active, completed) = groupDownloads(displayRows)
    val usage = DownloadQuota.usage(used, reserved, preferences.capGb)
    val notificationsDenied = !com.dev.Tvivo.ui.adaptive.LocalIsTelevision.current && Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        context.getSharedPreferences("download-notifications", 0).getBoolean("requested", false)
    Column(Modifier.fillMaxSize().background(AppColors.Bg).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.downloads_title), color = AppColors.Ink, style = TvType.title, modifier = Modifier.weight(1f))
            Action(stringResource(R.string.downloads_settings), "downloads-settings", onOpenSettings)
        }
        Text(stringResource(R.string.downloads_usage, usage.usedGb, usage.capGb.toDouble(), usage.reservedGb),
            color = AppColors.Dim, style = TvType.body, modifier = Modifier.testTag("downloads-usage"))
        if (notificationsDenied) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.download_notifications_hint), color = AppColors.Dim, style = TvType.label, modifier = Modifier.weight(1f))
            Action(stringResource(R.string.download_notifications_allow), "allow-download-notifications") {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        if (retryBlocked) Text(stringResource(R.string.downloads_retry_blocked), color = AppColors.AccentText,
            style = TvType.label, modifier = Modifier.testTag("downloads-retry-blocked"))
        if (displayRows.isEmpty()) DownloadsEmptyState()
        if (active.isNotEmpty()) {
            Text(stringResource(R.string.downloads_active), color = AppColors.AccentText, style = TvType.title)
            DownloadRows(active, onAction = { row, action ->
                scope.launch { when (action) {
                    "pause" -> DownloadController.pause(context, row)
                    "resume" -> DownloadController.resume(context, row)
                    "cancel" -> DownloadController.cancel(context, row)
                    "retry" -> { retryBlocked = DownloadController.retry(context, row) is com.dev.Tvivo.download.DownloadRepository.EnqueueResult.Rejected }
                    else -> confirmRows = listOf(row)
                } }
            }, onDeleteSeries = { confirmRows = it }, onPlayDownloaded = onPlayDownloaded)
        }
        if (completed.isNotEmpty()) {
            Text(stringResource(R.string.downloads_completed_group), color = AppColors.AccentText, style = TvType.title)
            DownloadRows(completed, onAction = { row, _ -> confirmRows = listOf(row) },
                onDeleteSeries = { confirmRows = it }, onPlayDownloaded = onPlayDownloaded)
        }
    }
    confirmRows?.let { toDelete ->
        val episodes = toDelete.size > 1
        val body = if (episodes) stringResource(R.string.downloads_delete_series_body, toDelete.size)
            else stringResource(R.string.downloads_delete_body)
        ConfirmDialog(
            title = stringResource(if (episodes) R.string.downloads_delete_series_title else R.string.downloads_delete_title),
            consequence = body,
            confirmLabel = stringResource(R.string.downloads_delete), safeLabel = stringResource(R.string.download_cancel),
            onConfirm = { scope.launch { toDelete.forEach { repo.remove(it) }; confirmRows = null } },
            onDismiss = { confirmRows = null })
    }
}

@Composable
internal fun DownloadsEmptyState() {
    Text(stringResource(R.string.downloads_empty), color = AppColors.Dim, style = TvType.body,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp).testTag("downloads-empty"))
}

@Composable
internal fun DownloadRows(rows: List<DownloadDisplayRow>, onAction: (DownloadEntity, String) -> Unit,
    onDeleteSeries: (List<DownloadEntity>) -> Unit, onPlayDownloaded: (DownloadEntity, String) -> Unit) {
    val groups = rows.groupBy { if (it.row.kind == "episode") it.row.seriesId else 0 }
    groups.forEach { (_, grouped) ->
        val series = grouped.firstOrNull()?.seriesTitle.orEmpty()
        if (series.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(series, color = AppColors.Ink, style = TvType.title, modifier = Modifier.weight(1f))
            val episodes = grouped.filter { it.row.kind == "episode" }.map { it.row }
            if (episodes.size > 1) Action(stringResource(R.string.downloads_delete_all_episodes, episodes.size), "delete-all-episodes-${episodes.first().seriesId}") {
                onDeleteSeries(episodes)
            }
        }
        grouped.forEach { item ->
            val row = item.row
            Column(Modifier.fillMaxWidth().background(AppColors.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, color = AppColors.Ink, style = TvType.body, maxLines = 3)
                Text("${formatBytes(row.partialBytes)} / ${row.expectedBytes?.let(::formatBytes) ?: "—"} · ${stringResource(stateString(row))}", color = AppColors.Dim, style = TvType.label)
                if (row.state == "DOWNLOADING" || row.state == "COMPLETED") {
                    val percent = if (row.state == "COMPLETED") 100 else if ((row.expectedBytes ?: 0L) > 0) ((row.partialBytes * 100 / row.expectedBytes!!).coerceIn(0, 99)).toInt() else 0
                    Text(stringResource(R.string.downloads_progress_percent, percent), color = AppColors.Dim, style = TvType.label)
                }
                if (row.state == "FAILED") Text(stringResource(when (row.failureReason?.lowercase()) {
                    "authorization", "unauthorized", "http_401", "http_403" -> R.string.downloads_failed_authorization
                    "storage", "storage_full", "space" -> R.string.downloads_failed_storage
                    else -> R.string.downloads_failed_generic
                }), color = AppColors.AccentText, style = TvType.label)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (row.state) {
                        "QUEUED", "DOWNLOADING" -> { Action(stringResource(R.string.download_pause_action), "pause-${row.mediaId}") { onAction(row, "pause") }; Action(stringResource(R.string.download_cancel_action), "cancel-${row.mediaId}") { onAction(row, "cancel") } }
                        "PAUSED" -> { Action(stringResource(R.string.downloads_resume), "resume-${row.mediaId}") { onAction(row, "resume") }; Action(stringResource(R.string.download_cancel_action), "cancel-${row.mediaId}") { onAction(row, "cancel") } }
                        "FAILED" -> { Action(stringResource(R.string.downloads_retry), "retry-${row.mediaId}") { onAction(row, "retry") }; Action(stringResource(R.string.downloads_delete), "delete-${row.mediaId}") { onAction(row, "delete") } }
                        "COMPLETED" -> {
                            if (ENABLE_DOWNLOADED_PLAY) Action(stringResource(R.string.downloads_play), "play-${row.mediaId}") {
                                onPlayDownloaded(row, item.title)
                            }
                            Action(stringResource(R.string.downloads_delete), "delete-${row.mediaId}") { onAction(row, "delete") }
                        }
                        else -> Action(stringResource(R.string.downloads_delete), "delete-${row.mediaId}") { onAction(row, "delete") }
                    }
                }
            }
        }
    }
}

@Composable private fun Action(label: String, tag: String, onClick: () -> Unit) =
    Box(Modifier.heightIn(min = 48.dp).testTag(tag).semantics { role = Role.Button; contentDescription = label }
        .tvFocusFrame().tvClickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = AppColors.Ink, style = TvType.label)
    }

private fun stateString(row: DownloadEntity) = when (row.state) {
    "QUEUED" -> R.string.download_queued; "DOWNLOADING" -> R.string.download_progress
    "PAUSED" -> R.string.download_paused; "COMPLETED" -> R.string.download_completed
    "FAILED" -> R.string.download_failed; else -> R.string.download_cancel_action
}
private fun formatBytes(bytes: Long): String = if (bytes >= 1_000_000_000) "%.2f GB".format(bytes / 1_000_000_000.0) else "%.0f MB".format(bytes / 1_000_000.0)
