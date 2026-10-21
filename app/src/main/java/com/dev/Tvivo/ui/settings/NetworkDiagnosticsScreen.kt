package com.dev.Tvivo.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.content.ComponentName
import android.os.Bundle
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.AdaptiveActionButton as Button
import com.dev.Tvivo.R
import com.dev.Tvivo.diagnostics.CheckState
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.diagnostics.DropPattern
import com.dev.Tvivo.diagnostics.Finding
import com.dev.Tvivo.diagnostics.NetworkDiagnosis
import com.dev.Tvivo.diagnostics.NetworkDiagnosticsRuntime
import com.dev.Tvivo.diagnostics.NetworkEvent
import com.dev.Tvivo.diagnostics.NetworkEventKind
import com.dev.Tvivo.diagnostics.NetworkEventSource
import com.dev.Tvivo.diagnostics.NetworkEventStore
import com.dev.Tvivo.diagnostics.NetworkSnapshot
import com.dev.Tvivo.diagnostics.ProbeResults
import com.dev.Tvivo.diagnostics.RecentEventSummary
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.diagnostics.NetworkCheckRunner
import com.dev.Tvivo.diagnostics.NetworkCheckReport
import com.dev.Tvivo.diagnostics.NetworkPlaybackState
import com.dev.Tvivo.diagnostics.DeepStreamCheckGate
import com.dev.Tvivo.diagnostics.DeepStreamCheckRunner
import com.dev.Tvivo.diagnostics.DeepStreamMeasurement
import com.dev.Tvivo.diagnostics.DeepStreamPhase
import com.dev.Tvivo.diagnostics.DeepStreamProgress
import com.dev.Tvivo.diagnostics.DeepStreamItem
import com.dev.Tvivo.playback.PlaybackCommands
import com.dev.Tvivo.playback.PlaybackService
import com.dev.Tvivo.data.StreamUrlBuilder
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.ui.common.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.dev.Tvivo.diagnostics.ProbeId
import com.dev.Tvivo.diagnostics.ProbeStatus
import com.dev.Tvivo.diagnostics.gatewayProbe
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.adaptiveSpacing
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

@Composable
fun NetworkDiagnosticsScreen(accountPartition: String, credentials: Credentials) {
    val context = LocalContext.current
    val reader = remember(context) { NetworkDiagnosticsRuntime.start(context.applicationContext) }
    val snapshot by reader.snapshot.collectAsStateWithLifecycle()
    val allEvents by NetworkEventStore.events.collectAsStateWithLifecycle()
    val events = remember(allEvents, accountPartition) {
        NetworkEventStore.forAccount(accountPartition)
    }
    val recent = remember(events) { summarize(events, System.currentTimeMillis()) }
    var checkReport by remember { mutableStateOf<NetworkCheckReport?>(null) }
    var deepMeasurement by remember { mutableStateOf<DeepStreamMeasurement?>(null) }
    var deepProgress by remember { mutableStateOf<DeepStreamProgress?>(null) }
    val findings = remember(snapshot, recent, checkReport, deepMeasurement) {
        val base = checkReport?.probes ?: ProbeResults()
        val deep = deepMeasurement
        val probes = if (deep == null) base else base.copy(providerStatus = deep.httpStatus ?: base.providerStatus,
            streamThroughputMbps = deep.throughputMbps, streamStalls = deep.stalls,
            streamStallDurationSeconds = deep.stallDurationMs?.let { (it + 500) / 1_000 },
            streamBytesAtStallMb = deep.bytesAtStall?.div(1_000_000.0),
            streamRetries = deep.retries, streamBitrateMbps = deep.streamBitrateMbps)
        NetworkDiagnosis.diagnose(snapshot, probes, recent)
    }
    val spacing = adaptiveSpacing()
    val runCheckFocus = remember { FocusRequester() }
    val runCheckDescription = stringResource(R.string.network_run_check_accessibility)
    val copyReportDescription = stringResource(R.string.network_copy_accessibility)
    val exportReportDescription = stringResource(R.string.network_export_accessibility)
    val scope = rememberCoroutineScope()
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<List<com.dev.Tvivo.diagnostics.ProbeProgress>>(emptyList()) }
    var meterConfirm by remember { mutableStateOf(false) }
    var deepConfirm by remember { mutableStateOf(false) }
    var deepJob by remember { mutableStateOf<Job?>(null) }
    var candidate by remember { mutableStateOf<DeepStreamItem?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(accountPartition) {
        candidate = withContext(Dispatchers.IO) {
            val db = AppDatabase.get(context)
            db.vodDao().deepCheckCandidate(accountPartition)?.let { DeepStreamItem(it.streamId, it.containerExtension ?: "mp4", false, it.durationMinutes) }
                ?: db.liveDao().deepCheckCandidate(accountPartition)?.let { DeepStreamItem(it.streamId, it.ext ?: "ts", true) }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) deepJob?.cancel() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); deepJob?.cancel() }
    }
    val runner = remember(context) { NetworkCheckRunner(gateway = gatewayProbe(context)) }
    val startCheck: (Boolean) -> Unit = { confirmedMetered ->
        meterConfirm = false
        val latestSnapshot = reader.refresh()
        checkJob = scope.launch {
            progress = emptyList()
            checkReport = runner.run(latestSnapshot, credentials, NetworkPlaybackState.active, confirmedMetered,
                selectedTrackBitrateMbps = NetworkPlaybackState.selectedTrackBitrateMbps) { item ->
                progress = (progress.filterNot { it.id == item.id } + item)
            }.also { checkReport = it }
        }
    }
    LaunchedEffect(Unit) { runCatching { runCheckFocus.requestFocus() } }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.Bg)
            .then(if (LocalIsTelevision.current) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = spacing.outerGutter))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (LocalIsTelevision.current) 96.dp else 0.dp,
                vertical = if (LocalIsTelevision.current) 56.dp else 16.dp)
    ) {
        Text(stringResource(R.string.network_diagnostics_title), color = AppColors.Ink, style = TvType.display)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.network_diagnostics_passive_disclaimer), color = AppColors.Dim, style = TvType.body)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.network_endpoint_disclosure), color = AppColors.Dim, style = TvType.caption)
        Button(enabled = deepJob?.isActive != true, onClick = {
            if (checkJob?.isActive == true) checkJob?.cancel()
            else if (snapshot.metered == true) meterConfirm = true else startCheck(false)
        }, modifier = Modifier.focusRequester(runCheckFocus)
            .testTag("networkRunCheck").semantics { contentDescription = runCheckDescription }) {
            Text(stringResource(if (checkJob?.isActive == true) R.string.network_cancel_check else R.string.network_run_check))
        }
        if (candidate != null) {
            Button(enabled = checkJob?.isActive != true, onClick = { if (deepJob?.isActive == true) deepJob?.cancel() else deepConfirm = true },
                modifier = Modifier.testTag("networkDeepStreamCheck")) {
                Text(stringResource(if (deepJob?.isActive == true) R.string.network_cancel_check else R.string.network_deep_stream_check))
            }
        }
        deepProgress?.let { state ->
            val label = when (state.phase) {
                DeepStreamPhase.STOPPING -> stringResource(R.string.network_deep_stopping)
                DeepStreamPhase.COOLDOWN -> stringResource(R.string.network_deep_cooldown, (state.remainingMs + 999) / 1000)
                DeepStreamPhase.CONNECTING -> stringResource(R.string.network_deep_connecting)
                DeepStreamPhase.MEASURING -> stringResource(R.string.network_deep_measuring, state.measurement?.bytes ?: 0)
                DeepStreamPhase.COMPLETE -> stringResource(R.string.network_deep_complete)
                DeepStreamPhase.FAILED -> stringResource(
                    if ((state.measurement?.stalls ?: 0) > 0) R.string.network_deep_stalled
                    else R.string.network_deep_failed
                )
                DeepStreamPhase.CANCELLED -> stringResource(R.string.network_deep_cancelled)
            }
            Text(label, color = AppColors.Dim, style = TvType.body, modifier = Modifier.testTag("networkDeepProgress"))
        }
        deepMeasurement?.let { result ->
            Text(stringResource(R.string.network_deep_timing, result.httpStatus?.toString() ?: "—",
                result.connectMs?.let { "$it ms" } ?: "—", result.firstByteMs?.let { "$it ms" } ?: "—"),
                color = AppColors.Ink, style = TvType.label)
            val rate = result.throughputMbps?.let { "%.2f Mbps".format(java.util.Locale.ROOT, it) }
                ?: stringResource(R.string.network_rate_unavailable)
            val bitrate = result.streamBitrateMbps?.let {
                stringResource(R.string.network_estimated_bitrate, it)
            } ?: stringResource(R.string.network_bitrate_unavailable)
            Text(stringResource(R.string.network_deep_transfer, result.bytes, rate, bitrate,
                result.stalls, result.retries),
                color = AppColors.Dim, style = TvType.label)
            if (result.stalls > 0 && result.stallDurationMs != null && result.bytesAtStall != null) {
                Text(stringResource(R.string.network_deep_stall_detail,
                    (result.stallDurationMs + 500) / 1_000, result.bytesAtStall / 1_000_000.0),
                    color = AppColors.Dim, style = TvType.label)
            }
        }
        if (deepConfirm) ConfirmDialog(
            title = stringResource(R.string.network_deep_confirm_title),
            consequence = stringResource(R.string.network_deep_confirm_message,
                deepEstimateSeconds(NetworkPlaybackState.active, DeepStreamCheckGate.cooldownRemainingMs())),
            confirmLabel = stringResource(R.string.network_deep_confirm_start),
            safeLabel = stringResource(R.string.network_deep_confirm_cancel),
            onDismiss = { deepConfirm = false },
            onConfirm = {
                deepConfirm = false
                val chosen = candidate ?: return@ConfirmDialog
                deepJob = scope.launch {
                    deepMeasurement = null
                    deepProgress = DeepStreamProgress(DeepStreamPhase.STOPPING)
                    val url = if (chosen.isLive) StreamUrlBuilder.live(credentials, chosen.id, chosen.extension)
                        else StreamUrlBuilder.movie(credentials, chosen.id, chosen.extension)
                    val deepRunner = DeepStreamCheckRunner(stopPlayback = {
                        if (!NetworkPlaybackState.active) false else withContext(Dispatchers.IO) {
                            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                            val controller = MediaController.Builder(context, token).buildAsync().get(8, TimeUnit.SECONDS)
                            try {
                                val result = controller.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.STOP), Bundle.EMPTY)
                                    .get(12, TimeUnit.SECONDS)
                                check(result.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                                    "Playback stop was not acknowledged"
                                }
                                true
                            } finally { controller.release() }
                        }
                    })
                    try {
                        val measurement = deepRunner.run(url) { deepProgress = it }
                        val estimatedBitrate = measurement.contentLengthBytes?.let { totalBytes -> chosen.durationMinutes
                            ?.takeIf { it > 0 }?.let { totalBytes * 8.0 / (it * 60.0) / 1_000_000.0 } }
                        deepMeasurement = measurement.copy(streamBitrateMbps = estimatedBitrate)
                    }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        deepMeasurement = DeepStreamMeasurement(failure = com.dev.Tvivo.diagnostics.DeepStreamFailure.NETWORK)
                        deepProgress = DeepStreamProgress(DeepStreamPhase.FAILED, measurement = deepMeasurement)
                    }
                }
            })
        if (meterConfirm) {
            Text(stringResource(R.string.network_metered_confirm), color = AppColors.Ink, style = TvType.body)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { meterConfirm = false }) { Text(stringResource(R.string.network_metered_no)) }
                Button(onClick = { startCheck(true) }) { Text(stringResource(R.string.network_metered_yes)) }
            }
        }
        progress.forEach { item ->
            val status = stringResource(when (item.status) {
                com.dev.Tvivo.diagnostics.ProbeStatus.RUNNING -> R.string.network_status_running
                com.dev.Tvivo.diagnostics.ProbeStatus.PASS -> R.string.network_status_pass
                com.dev.Tvivo.diagnostics.ProbeStatus.FAIL -> R.string.network_status_fail
                com.dev.Tvivo.diagnostics.ProbeStatus.SKIPPED -> R.string.network_status_skipped
                com.dev.Tvivo.diagnostics.ProbeStatus.CANCELLED -> R.string.network_status_cancelled
            })
            Text("${probeTitle(item.id)}: $status${item.detail.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                color = AppColors.Dim, style = TvType.label, modifier = Modifier.testTag("networkTest_${item.id.name.lowercase()}"))
        }
        checkReport?.let { report ->
            Text(stringResource(R.string.network_probe_results), color = AppColors.Ink, style = TvType.title)
            report.tests.forEach { result ->
                val status = stringResource(when (result.status) {
                    com.dev.Tvivo.diagnostics.ProbeStatus.RUNNING -> R.string.network_status_running
                    com.dev.Tvivo.diagnostics.ProbeStatus.PASS -> R.string.network_status_pass
                    com.dev.Tvivo.diagnostics.ProbeStatus.FAIL -> R.string.network_status_fail
                    com.dev.Tvivo.diagnostics.ProbeStatus.SKIPPED -> R.string.network_status_skipped
                    com.dev.Tvivo.diagnostics.ProbeStatus.CANCELLED -> R.string.network_status_cancelled
                })
                Text("${probeTitle(result.id)}: $status · ${result.detail}", color = AppColors.Dim, style = TvType.label)
            }
        }
        findings.firstOrNull()?.let { strongest ->
            Text(stringResource(R.string.network_summary_finding, findingTitle(strongest.id)),
                color = AppColors.Ink, style = TvType.title)
        }
        Text(stringResource(R.string.network_check_age,
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.capturedAtMs)),
            durationText(context, (System.currentTimeMillis() - snapshot.capturedAtMs).coerceAtLeast(0L))),
            color = AppColors.Dim, style = TvType.label, modifier = Modifier.testTag("networkSummary"))
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.network_connection), color = AppColors.Ink, style = TvType.title)
        InfoLine(stringResource(R.string.network_route), routeLabel(snapshot.route))
        InfoLine(stringResource(R.string.network_android_validation), triLabel(snapshot.validated))
        InfoLine(stringResource(R.string.network_captive_portal), triLabel(snapshot.captivePortal))
        InfoLine(stringResource(R.string.network_wifi_signal), snapshot.rssiDbm?.let { "$it dBm" }
            ?: stringResource(R.string.network_wifi_details_unavailable))
        InfoLine(stringResource(R.string.network_wifi_band), bandLabel(snapshot.wifiBand))
        InfoLine(stringResource(R.string.network_receive_link), snapshot.receiveLinkMbps?.let { "$it Mbps" } ?: stringResource(R.string.network_unknown))
        InfoLine(stringResource(R.string.network_ip_assigned), triLabel(snapshot.ipAssigned))
        InfoLine(stringResource(R.string.network_router_reachability), stringResource(R.string.network_not_checked))
        InfoLine(stringResource(R.string.network_dns_servers), snapshot.dnsServerCount?.toString() ?: stringResource(R.string.network_unknown))
        InfoLine(stringResource(R.string.network_proxy), triLabel(snapshot.proxyPresent))
        InfoLine(stringResource(R.string.network_private_dns), privateDnsLabel(snapshot.privateDns))
        InfoLine(stringResource(R.string.network_vpn), triLabel(snapshot.vpnPresent))
        InfoLine(stringResource(R.string.network_static_ip), stringResource(R.string.network_not_available_android))
        InfoLine(stringResource(R.string.network_metered), triLabel(snapshot.metered))
        InfoLine(stringResource(R.string.network_background_restricted), triLabel(snapshot.backgroundRestricted))
        InfoLine(stringResource(R.string.network_automatic_time), triLabel(snapshot.automaticTimeEnabled))
        InfoLine(stringResource(R.string.network_clock_offset), stringResource(R.string.network_not_measured))
        Text(stringResource(R.string.network_privacy_note), color = AppColors.Dim, style = TvType.caption)

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.network_findings), color = AppColors.Ink, style = TvType.title)
        if (findings.isEmpty()) Text(stringResource(R.string.network_no_problem_found), color = AppColors.Dim, style = TvType.body)
        findings.forEach { finding -> FindingCard(finding, snapshot, recent) }

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.network_recent_problems), color = AppColors.Ink, style = TvType.title,
            modifier = Modifier.testTag("networkRecentProblems"))
        val problems = events.filter { it.kind !in setOf(NetworkEventKind.AVAILABLE, NetworkEventKind.VALIDATION_CHANGED, NetworkEventKind.LINK_CHANGED) }
        if (problems.isEmpty()) {
            Text(stringResource(R.string.network_no_recent_problems), color = AppColors.Dim, style = TvType.body)
        } else {
            Text(stringResource(R.string.network_drops_stats, recent.dropPattern.dropsIn24Hours,
                recent.dropPattern.longestDropMs?.let { durationText(context, it) } ?: stringResource(R.string.network_unknown),
                recent.dropPattern.averageGapMs?.let { durationText(context, it) } ?: stringResource(R.string.network_unknown)),
                color = AppColors.Dim, style = TvType.body)
            problems.groupBy { it.kind }.values
                .sortedByDescending { group -> group.maxOf { it.timestampMs } }
                .forEach { ProblemGroup(it) }
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.network_timeline), color = AppColors.Ink, style = TvType.title,
            modifier = Modifier.testTag("networkTimeline"))
        val timeline = events.filter { it.source == NetworkEventSource.CONNECTIVITY }.take(100)
        if (timeline.isEmpty()) Text(stringResource(R.string.network_no_timeline), color = AppColors.Dim, style = TvType.body)
        else timeline.forEach { EventLine(it) }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Network diagnostics", report(snapshot, findings, events, checkReport, deepMeasurement)))
                Toast.makeText(context, R.string.network_copied, Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.testTag("networkCopyReport")
                .semantics { contentDescription = copyReportDescription }) { Text(stringResource(R.string.network_copy_report)) }
            Button(onClick = { DiagnosticLog.export(context, report(snapshot, findings, events, checkReport, deepMeasurement)) },
                modifier = Modifier.testTag("networkExportReport")
                    .semantics { contentDescription = exportReportDescription }) { Text(stringResource(R.string.network_export_report)) }
        }
    }
}

@Composable private fun probeTitle(id: ProbeId): String = stringResource(when (id) {
    ProbeId.DNS -> R.string.network_test_dns
    ProbeId.INTERNET -> R.string.network_test_internet
    ProbeId.GATEWAY -> R.string.network_test_gateway
    ProbeId.PANEL -> R.string.network_test_panel
    ProbeId.CLOCK -> R.string.network_test_clock
    ProbeId.IP_FAMILIES -> R.string.network_test_ip_families
    ProbeId.REQUEST_SAMPLE -> R.string.network_test_request_sample
    ProbeId.THROUGHPUT -> R.string.network_test_throughput
})

@Composable private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = AppColors.Dim, style = TvType.label, modifier = Modifier.weight(1f))
        Text(value, color = AppColors.Ink, style = TvType.label, modifier = Modifier.weight(1f))
    }
}

@Composable private fun FindingCard(finding: Finding, snapshot: NetworkSnapshot, recent: RecentEventSummary) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("networkFinding_${finding.id}")) {
        Text(findingTitle(finding.id), color = if (finding.severity.ordinal < 2) AppColors.Error else AppColors.Ink, style = TvType.title)
        Text(findingEvidence(finding, snapshot, recent), color = AppColors.Dim, style = TvType.body)
        finding.nextSteps.map { stepLabel(it) }.forEachIndexed { index, step ->
            Text("${index + 1}. $step", color = AppColors.Ink, style = TvType.label)
        }
        Text(stringResource(when (finding.id) {
            "wifi_radio_firmware_pattern" -> R.string.limitation_radio
            "provider_403" -> R.string.limitation_403
            "provider_path_possible" -> R.string.limitation_provider
            "network_configuration_present" -> R.string.limitation_config
            "weak_wifi_signal", "wifi_2_4ghz" -> R.string.limitation_wifi
            "request_samples_unstable" -> R.string.limitation_samples
            "throughput_below_track" -> R.string.limitation_throughput
            else -> R.string.limitation_generic
        }), color = AppColors.Dim, style = TvType.caption)
    }
}

@Composable private fun findingEvidence(finding: Finding, snapshot: NetworkSnapshot, recent: RecentEventSummary): String =
    when (finding.id) {
        "weak_wifi_signal" -> stringResource(R.string.evidence_rssi, snapshot.rssiDbm ?: 0)
        "clock_skew" -> if (snapshot.automaticTimeEnabled == false) stringResource(R.string.evidence_auto_time_off)
            else stringResource(R.string.evidence_passive)
        "wifi_radio_firmware_pattern" -> stringResource(R.string.evidence_radio, recent.dropPattern.dropsIn24Hours,
            recent.dropPattern.networksAffected ?: 0)
        "repeated_drops_unknown_cause" -> stringResource(R.string.evidence_drops, recent.dropPattern.dropsIn24Hours,
            recent.dropPattern.longestDropMs?.let { it / 60_000 } ?: 0)
        "provider_403" -> stringResource(R.string.evidence_status, recent.http403Count.coerceAtLeast(1), 403)
        "provider_429" -> stringResource(R.string.evidence_status, recent.http429Count.coerceAtLeast(1), 429)
        "provider_server_errors" -> stringResource(R.string.evidence_server, recent.serverErrorCount)
        "dns_failures" -> stringResource(R.string.evidence_dns, recent.dnsFailureCount)
        "wifi_router_unreachable" -> stringResource(R.string.evidence_router)
        "captive_portal" -> stringResource(R.string.evidence_captive)
        "internet_unvalidated" -> stringResource(R.string.evidence_internet)
        else -> stringResource(R.string.evidence_passive)
    }

@Composable private fun stepLabel(id: String): String = stringResource(when (id) {
    "check_network_settings" -> R.string.step_network_settings
    "reconnect" -> R.string.step_reconnect
    "router_if_others_fail" -> R.string.step_router
    "try_ethernet" -> R.string.step_ethernet
    "complete_wifi_sign_in" -> R.string.step_portal
    "check_router_internet" -> R.string.step_router_internet
    "retry_later" -> R.string.step_retry_later
    "move_tv_closer" -> R.string.step_move_tv
    "reduce_obstructions" -> R.string.step_reduce_obstructions
    "compare_5ghz" -> R.string.step_compare_5ghz
    "compare_ethernet" -> R.string.step_ethernet
    "try_streaming_device" -> R.string.step_streaming_device
    "forget_rejoin_wifi" -> R.string.step_rejoin
    "update_tv_firmware" -> R.string.step_firmware
    "compare_router_compatibility" -> R.string.step_radio_router
    "check_wifi_power_saving" -> R.string.step_radio_power
    "reduce_2_4ghz_load" -> R.string.step_radio_load
    "factory_reset_last" -> R.string.step_factory_reset
    "contact_tv_maker" -> R.string.step_tv_maker
    "check_other_devices" -> R.string.step_other_devices
    "wait_60_seconds" -> R.string.step_wait_connection
    "retry_once", "wait_briefly_retry" -> R.string.step_retry_once
    "check_account_access" -> R.string.step_access
    "wait_before_refresh" -> R.string.step_wait_refresh
    "compare_another_network" -> R.string.step_compare_network
    "share_redacted_report" -> R.string.step_share_report
    "private_dns_automatic" -> R.string.step_dns
    "restart_router" -> R.string.step_router
    "consider_router_dns" -> R.string.step_router_dns
    "check_ipv6_router" -> R.string.step_ipv6
    "ask_isp_ipv6" -> R.string.step_isp_ipv6
    "enable_automatic_time" -> R.string.step_auto_time
    "pause_downloads" -> R.string.step_pause_downloads
    "stop_downloads" -> R.string.step_stop_downloads
    "choose_lower_bitrate" -> R.string.step_lower_quality
    "compare_network_setting" -> R.string.step_compare_setting
    "allow_background_data" -> R.string.step_background_data
    else -> R.string.network_unknown
})

@Composable private fun EventLine(event: NetworkEvent) {
    val kind = eventKindLabel(event.kind)
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.timestampMs))
    val details = stringResource(R.string.network_event_details,
        event.route ?: stringResource(R.string.network_unknown),
        event.validated?.let { if (it) stringResource(R.string.network_yes) else stringResource(R.string.network_no) }
            ?: stringResource(R.string.network_unknown),
        event.rssiBand?.let { stringResource(when (it) { "strong" -> R.string.network_signal_strong; "medium" -> R.string.network_signal_medium; "weak" -> R.string.network_signal_weak; else -> R.string.network_unknown }) }
            ?: stringResource(R.string.network_unknown),
        event.ipAssigned?.let { if (it) stringResource(R.string.network_yes) else stringResource(R.string.network_no) }
            ?: stringResource(R.string.network_unknown))
    Text("$time · $kind${event.durationMs?.let { " · ${durationText(LocalContext.current, it)}" } ?: ""} · $details",
        color = AppColors.Dim, style = TvType.label)
}

@Composable private fun ProblemGroup(events: List<NetworkEvent>) {
    val first = events.minOf { it.timestampMs }
    val last = events.maxOf { it.timestampMs }
    val firstTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(first))
    val lastTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
    Text(stringResource(R.string.network_problem_group, eventKindLabel(events.first().kind), events.size,
        if (first == last) firstTime else "$firstTime – $lastTime"),
        color = AppColors.Dim, style = TvType.body)
}

@Composable private fun eventKindLabel(kind: NetworkEventKind): String = when (kind) {
        NetworkEventKind.PROCESS_GAP -> stringResource(R.string.network_event_gap)
        NetworkEventKind.AVAILABLE -> stringResource(R.string.network_event_available)
        NetworkEventKind.LOST -> stringResource(R.string.network_event_lost)
        NetworkEventKind.NETWORK_CHANGED -> stringResource(R.string.network_event_changed)
        NetworkEventKind.VALIDATION_CHANGED -> stringResource(R.string.network_event_validation)
        NetworkEventKind.LINK_CHANGED -> stringResource(R.string.network_event_link)
        NetworkEventKind.HTTP_403 -> "HTTP 403"; NetworkEventKind.HTTP_429 -> "HTTP 429"
        NetworkEventKind.HTTP_5XX -> stringResource(R.string.network_event_server_error)
        NetworkEventKind.DNS_FAILURE -> stringResource(R.string.network_event_dns)
        NetworkEventKind.TIMEOUT -> stringResource(R.string.network_event_timeout)
        NetworkEventKind.TLS_FAILURE -> stringResource(R.string.network_event_tls)
        NetworkEventKind.NETWORK_FAILURE -> stringResource(R.string.network_event_network_failure)
        NetworkEventKind.STALL -> stringResource(R.string.network_event_stall)
        NetworkEventKind.RETRY -> stringResource(R.string.network_event_retry)
        NetworkEventKind.HANDOFF_COOLDOWN_403 -> stringResource(R.string.network_event_handoff_403)
    }

@Composable private fun findingTitle(id: String): String = stringResource(when (id) {
    "no_route" -> R.string.finding_no_route
    "wifi_router_unreachable" -> R.string.finding_wifi_router_unreachable
    "captive_portal" -> R.string.finding_captive
    "internet_unvalidated" -> R.string.finding_internet_unvalidated
    "weak_wifi_signal" -> R.string.finding_weak_wifi
    "wifi_2_4ghz" -> R.string.finding_2_4ghz
    "wifi_radio_firmware_pattern" -> R.string.finding_wifi_radio
    "repeated_drops_unknown_cause" -> R.string.finding_repeated_drops
    "provider_403" -> R.string.finding_403
    "provider_429" -> R.string.finding_429
    "provider_path_possible" -> R.string.finding_provider_path
    "provider_server_errors" -> R.string.finding_server_errors
    "dns_failures" -> R.string.finding_dns
    "slow_or_failing_dns" -> R.string.finding_slow_dns
    "ipv6_path_may_fail" -> R.string.finding_ipv6
    "request_samples_unstable" -> R.string.finding_request_samples
    "throughput_below_track" -> R.string.finding_throughput
    "clock_skew" -> R.string.finding_clock
    "network_configuration_present" -> R.string.finding_config
    "metered_restricted" -> R.string.finding_metered
    "checks_look_ok" -> R.string.finding_ok
    else -> R.string.finding_incomplete
})

private fun summarize(events: List<NetworkEvent>, now: Long): RecentEventSummary {
    val recent = events.filter { now - it.timestampMs <= TimeUnit.HOURS.toMillis(24) }
    val lost = recent.filter { it.kind == NetworkEventKind.LOST }
    val recovered = recent.filter { it.kind == NetworkEventKind.AVAILABLE && it.durationMs != null }
    val orderedDrops = lost.map { it.timestampMs }.sorted()
    val averageGap = orderedDrops.zipWithNext { left, right -> right - left }.takeIf { it.isNotEmpty() }?.average()?.toLong()
    return RecentEventSummary(
        http403Count = recent.count { it.kind == NetworkEventKind.HTTP_403 },
        http429Count = recent.count { it.kind == NetworkEventKind.HTTP_429 },
        serverErrorCount = recent.count { it.kind == NetworkEventKind.HTTP_5XX },
        timeoutCount = recent.count { it.kind == NetworkEventKind.TIMEOUT },
        dnsFailureCount = recent.count { it.kind == NetworkEventKind.DNS_FAILURE },
        stallCount = recent.count { it.kind == NetworkEventKind.STALL },
        retryCount = recent.count { it.kind == NetworkEventKind.RETRY },
        handoffCooldown403Count = recent.count { it.kind == NetworkEventKind.HANDOFF_COOLDOWN_403 },
        dropPattern = DropPattern(dropsIn24Hours = lost.size,
            longestDropMs = recovered.mapNotNull { it.durationMs }.maxOrNull(),
            averageGapMs = averageGap,
            recoveredAfterReconnect = recovered.size, networksAffected = null, otherDevicesHealthy = null)
    )
}

private fun report(snapshot: NetworkSnapshot, findings: List<Finding>, events: List<NetworkEvent>, checkReport: NetworkCheckReport? = null,
                   deepMeasurement: DeepStreamMeasurement? = null) = buildString {
    appendLine(when {
        deepMeasurement != null -> "Network diagnostics (passive observations, quick check, and deep stream check)"
        checkReport != null -> "Network diagnostics (passive observations and user-started quick check)"
        else -> "Network diagnostics (passive observations)"
    })
    appendLine("Captured at ${snapshot.capturedAtMs}; route=${snapshot.route}; validated=${snapshot.validated}; band=${snapshot.wifiBand}; RSSI=${snapshot.rssiDbm ?: "unknown"}")
    appendLine("No hostnames, IP addresses, SSIDs, titles, URLs, or credentials are included.")
    findings.forEach { appendLine("${it.id}: ${it.title}; ${it.evidence}") }
    checkReport?.let { report ->
        appendLine("Quick check results (endpoints and account identifiers redacted):")
        report.tests.forEach { appendLine("${it.id}\t${it.status}\t${it.elapsedMs ?: ""} ms\t${it.detail}") }
        appendLine("Gateway method: ${report.gatewayMethod ?: "unavailable"}; throughput bytes capped at 3145728: ${report.throughputBytes}")
        appendLine("Measured throughput Mbps: ${report.probes.throughputMbps ?: "unknown"}; selected-track bitrate estimate Mbps: ${report.probes.selectedTrackBitrateMbps ?: "unknown"}")
    }
    deepMeasurement?.let { result ->
        appendLine("Deep stream check results (item title, provider address, URL, and credentials omitted):")
        appendLine("HTTP status: ${result.httpStatus ?: "unknown"}; connect: ${result.connectMs ?: "unknown"} ms; first byte: ${result.firstByteMs ?: "unknown"} ms")
        appendLine("Sample duration: ${result.durationMs} ms; bytes: ${result.bytes}; content length: ${result.contentLengthBytes ?: "unknown"}; stream throughput Mbps: ${result.throughputMbps ?: "unknown"}; estimated average bitrate Mbps: ${result.streamBitrateMbps ?: "unknown"}; stalls: ${result.stalls}; retries: ${result.retries}; outcome: ${result.failure ?: "complete"}")
    }
    appendLine("Recent timeline:")
    events.take(100).forEach { event ->
        appendLine("${event.timestampMs}\t${event.source}\t${event.kind}\t${event.statusCode ?: ""}\t${event.durationMs ?: ""}\t${event.route ?: ""}\t${event.validated ?: ""}\t${event.rssiBand ?: ""}\t${event.ipAssigned ?: ""}")
    }
}

private fun deepEstimateSeconds(playbackActive: Boolean, cooldownRemainingMs: Long): Int {
    val totalMs = (if (playbackActive) 60_000L else cooldownRemainingMs) + 26_000L
    return (((totalMs + 9_999L) / 10_000L) * 10).toInt()
}

@Composable private fun routeLabel(route: NetworkSnapshot.Route) = stringResource(when (route) {
    NetworkSnapshot.Route.NONE -> R.string.network_route_none; NetworkSnapshot.Route.WIFI -> R.string.network_route_wifi
    NetworkSnapshot.Route.ETHERNET -> R.string.network_route_ethernet; NetworkSnapshot.Route.CELLULAR -> R.string.network_route_cellular
    NetworkSnapshot.Route.VPN -> R.string.network_route_vpn; NetworkSnapshot.Route.OTHER -> R.string.network_route_other
    NetworkSnapshot.Route.UNKNOWN -> R.string.network_unknown
})
@Composable private fun bandLabel(band: NetworkSnapshot.WifiBand) = stringResource(when (band) {
    NetworkSnapshot.WifiBand.GHZ_2_4 -> R.string.network_band_24; NetworkSnapshot.WifiBand.GHZ_5 -> R.string.network_band_5
    NetworkSnapshot.WifiBand.GHZ_6 -> R.string.network_band_6; NetworkSnapshot.WifiBand.UNKNOWN -> R.string.network_unknown
})
@Composable private fun privateDnsLabel(mode: NetworkSnapshot.PrivateDns) = stringResource(when (mode) {
    NetworkSnapshot.PrivateDns.OFF -> R.string.network_dns_off; NetworkSnapshot.PrivateDns.AUTOMATIC -> R.string.network_dns_automatic
    NetworkSnapshot.PrivateDns.STRICT -> R.string.network_dns_strict; NetworkSnapshot.PrivateDns.UNKNOWN -> R.string.network_unknown
})
@Composable private fun triLabel(value: Boolean?): String = when (value) {
    true -> stringResource(R.string.network_yes); false -> stringResource(R.string.network_no)
    null -> stringResource(R.string.network_unknown)
}
private fun durationText(context: Context, ms: Long): String = when {
    ms >= TimeUnit.HOURS.toMillis(1) -> context.getString(R.string.network_duration_hours, ms / TimeUnit.HOURS.toMillis(1))
    ms >= TimeUnit.MINUTES.toMillis(1) -> context.getString(R.string.network_duration_minutes, ms / TimeUnit.MINUTES.toMillis(1))
    else -> context.getString(R.string.network_duration_seconds, ms / 1000)
}

@Suppress("unused") private fun sourceName(source: NetworkEventSource) = source.name
