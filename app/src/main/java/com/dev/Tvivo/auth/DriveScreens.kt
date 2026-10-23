package com.dev.Tvivo.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.TYPE_MUSIC
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.source.drive.BootstrapStep
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.common.ConfirmDialog
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private fun BootstrapStep.title(): String = when (this) {
    BootstrapStep.FINDING_LIBRARY -> "Finding library"
    BootstrapStep.CREATING_FOLDERS -> "Creating folders"
    BootstrapStep.ADDING_SAMPLE_LINKS -> "Adding sample links"
    BootstrapStep.OPENING_SAVED_LIBRARY -> "Opening saved library"
}

@Composable
fun DriveFlowScreen(
    state: DriveFlowState,
    onRefreshCode: () -> Unit,
    onRetry: () -> Unit,
    onChooseFolder: (String) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler(onBack = onCancel)
    val primaryFocus = remember { FocusRequester() }
    val tv = LocalIsTelevision.current
    LaunchedEffect(state::class) { runCatching { primaryFocus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().background(AppColors.Bg).verticalScroll(rememberScrollState())
            .padding(horizontal = if (tv) 96.dp else 24.dp, vertical = if (tv) 64.dp else 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Google Drive", style = TvType.display, color = AppColors.Ink)
        when (state) {
            DriveFlowState.Idle, DriveFlowState.RequestingCode -> {
                Text("Connecting to Google Drive…", style = TvType.body, color = AppColors.Dim)
            }
            is DriveFlowState.DeviceCode -> {
                var now by remember(state.expiresAt) { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(state.expiresAt) {
                    while (now < state.expiresAt) { delay(1000); now = System.currentTimeMillis() }
                }
                Text("On another device, open:", style = TvType.body, color = AppColors.Dim)
                Text(state.url, style = TvType.title, color = AppColors.Ink, modifier = Modifier.testTag("drive-code-url"))
                Text("Enter this code exactly as shown:", style = TvType.body, color = AppColors.Dim)
                Text(state.code, style = TvType.display, color = AppColors.AccentText,
                    modifier = Modifier.testTag("drive-code-value"))
                val remaining = ((state.expiresAt - now).coerceAtLeast(0L) + 999) / 1000
                Text("Code expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}",
                    style = TvType.body, color = AppColors.Dim, modifier = Modifier.testTag("drive-code-countdown"))
                Text("Waiting for approval…", style = TvType.body, color = AppColors.Dim)
                DriveAction("Refresh code", "Get a new code", Modifier.focusRequester(primaryFocus).testTag("drive-refresh-code"), onClick = onRefreshCode)
            }
            is DriveFlowState.Progress -> {
                Text(state.step.title(), style = TvType.title, color = AppColors.Ink,
                    modifier = Modifier.testTag("drive-bootstrap-step"))
                Text("Keep this screen open while your library is prepared.", style = TvType.body, color = AppColors.Dim)
            }
            is DriveFlowState.ChooseFolder -> {
                Text("Two Tvivo Library folders were found. Choose the one to open.",
                    style = TvType.body, color = AppColors.Ink)
                state.ids.forEachIndexed { index, id ->
                    DriveAction("Tvivo Library ${index + 1}", "Folder $id",
                        Modifier.then(if (index == 0) Modifier.focusRequester(primaryFocus) else Modifier)
                            .testTag("drive-folder-$index")) { onChooseFolder(id) }
                }
            }
            is DriveFlowState.Failed -> {
                Text(state.message, style = TvType.body, color = AppColors.Error,
                    modifier = Modifier.testTag("drive-flow-error"))
                DriveAction("Retry", "Try this step again", Modifier.focusRequester(primaryFocus).testTag("drive-retry"), onClick = onRetry)
            }
            is DriveFlowState.Ready -> Unit
        }
        DriveAction("Cancel", "Return to the previous source",
            Modifier.then(if (state !is DriveFlowState.DeviceCode && state !is DriveFlowState.ChooseFolder &&
                state !is DriveFlowState.Failed) Modifier.focusRequester(primaryFocus) else Modifier)
                .testTag("drive-cancel"), onClick = onCancel)
    }
}

@Composable
fun DriveHomeScreen(account: DriveAccount, seeded: Boolean, onAccount: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val db = remember(app) { AppDatabase.get(app) }
    val movies by remember(account.session.id) { db.categoryDao().observe(account.session.id, TYPE_VOD) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val series by remember(account.session.id) { db.categoryDao().observe(account.session.id, TYPE_SERIES) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val music by remember(account.session.id) { db.categoryDao().observe(account.session.id, TYPE_MUSIC) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(AppColors.Bg).verticalScroll(rememberScrollState())
        .padding(horizontal = if (LocalIsTelevision.current) 96.dp else 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Google Drive library", style = TvType.display, color = AppColors.Ink)
        Text(account.displayName ?: "Tvivo Library", style = TvType.title, color = AppColors.Dim)
        if (seeded) Text("Sample links are ready. Replace them by editing the Movies, Series and Music index.json files in Tvivo Library.",
            style = TvType.body, color = AppColors.Dim)
        if (movies.isEmpty() && series.isEmpty() && music.isEmpty())
            Text("No links yet. Add links to the JSON files in Tvivo Library, then re-sync.",
                style = TvType.body, color = AppColors.Dim)
        DriveAction("Account", "Google Drive, sync and source options", Modifier.focusRequester(focus).testTag("drive-account-entry"), onClick = onAccount)
        Text("Movies", style = TvType.title, color = AppColors.Ink)
        Text(movies.joinToString(" · ") { it.name }.ifBlank { "No links yet" }, style = TvType.body, color = AppColors.Dim)
        Text("Series", style = TvType.title, color = AppColors.Ink)
        Text(series.joinToString(" · ") { it.name }.ifBlank { "No links yet" }, style = TvType.body, color = AppColors.Dim)
        Text("Music", style = TvType.title, color = AppColors.Ink)
        Text(music.joinToString(" · ") { it.name }.ifBlank { "No links yet" }, style = TvType.body, color = AppColors.Dim)
    }
}

@Composable
fun DriveAccountScreen(
    account: DriveAccount,
    isSyncing: Boolean,
    syncMessage: String?,
    onResync: () -> Unit,
    onSwitchSource: () -> Unit,
    onDisconnect: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(AppColors.Bg).verticalScroll(rememberScrollState())
        .padding(horizontal = if (LocalIsTelevision.current) 96.dp else 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Google Drive", style = TvType.display, color = AppColors.Ink)
        account.displayName?.let { Text(it, style = TvType.title, color = AppColors.Ink) }
        account.email?.let { Text(it, style = TvType.body, color = AppColors.Dim) }
        Text("Folder: Tvivo Library", style = TvType.body, color = AppColors.Ink)
        Text("Last sync: " + (account.lastSyncAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never"),
            style = TvType.body, color = AppColors.Dim)
        DriveAction(if (isSyncing) "Re-syncing…" else "Re-sync now", "Check the library files for changes",
            Modifier.focusRequester(focus).testTag("drive-resync"), onClick = onResync)
        syncMessage?.let { Text(it, style = TvType.body, color = AppColors.Dim, modifier = Modifier.testTag("drive-sync-message")) }
        DriveAction("Switch source", "Keep this Google Drive library saved", Modifier.testTag("drive-switch-source"), onClick = onSwitchSource)
        Spacer(Modifier.height(24.dp))
        DriveAction("Disconnect Google Drive", "Remove this device's Google Drive access; saved titles and favourites stay on this device",
            Modifier.testTag("drive-disconnect")) { confirming = true }
    }
    if (confirming) ConfirmDialog("Disconnect Google Drive?",
        "This removes the Google Drive connection from this device. Saved titles and favourites stay here.",
        "Disconnect", "Keep connected", onConfirm = { confirming = false; onDisconnect() },
        onDismiss = { confirming = false })
}

@Composable
private fun DriveAction(label: String, hint: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusFrame()
        .tvClickable(enabled = enabled) { onClick() }
        .semantics(mergeDescendants = true) { contentDescription = "$label. $hint" }
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = TvType.title, color = AppColors.Ink)
        Text(hint, style = TvType.label, color = AppColors.Dim)
    }
}
