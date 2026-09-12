package com.dev.Tvivo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.diagnostics.CatalogProfiler
import com.dev.Tvivo.diagnostics.DbMetrics
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.diagnostics.RuntimeMetrics
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 5. The [DiagnosticLog] on screen, so a failure on a TV with no laptop attached is
 * still diagnosable.
 *
 * Read-only and unfocusable, like [SubscriptionScreen]: there is nothing here to act on,
 * and making the rows focusable would put ~200 stops between the user and the Back button.
 */
@Composable
fun DiagnosticsScreen() {
    val entries by DiagnosticLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalogProfile by remember { mutableStateOf<CatalogProfiler.Snapshot?>(null) }
    val metrics by produceState<DbMetrics.Snapshot?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            DbMetrics.collect(context.applicationContext, AppDatabase.get(context).openHelper.readableDatabase)
        }
    }
    val runtime by produceState<RuntimeMetrics.Snapshot?>(initialValue = null, context) {
        value = withContext(Dispatchers.Default) { RuntimeMetrics.collect(context.applicationContext) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = 96.dp, vertical = 64.dp)
    ) {
        Text(text = "Diagnostics", color = Palette.Ink, style = TvType.display)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Sanitized activity record. Newest first; retained across restarts. " +
                "Export never includes credentials, URLs, or titles.",
            color = Palette.Dim,
            style = TvType.body
        )
        Spacer(Modifier.height(24.dp))

        metrics?.let { snapshot ->
            Text(
                text = "Storage: ${DbMetrics.formatBytes(snapshot.totalBytes)} total  " +
                    "Database ${DbMetrics.formatBytes(snapshot.databaseFootprintBytes)}  " +
                    "Images ${DbMetrics.formatBytes(snapshot.imageCacheBytes)}",
                color = Palette.Dim,
                style = TvType.caption
            )
            if (snapshot.tableBytes.isNotEmpty()) {
                Text(
                    text = snapshot.tableBytes.entries.joinToString("  ") { (name, bytes) ->
                        "$name ${DbMetrics.formatBytes(bytes)}"
                    },
                    color = Palette.Dim,
                    style = TvType.caption
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        runtime?.let { snapshot ->
            Text(
                text = "Memory: Java ${DbMetrics.formatBytes(snapshot.javaUsedBytes)} / " +
                    "${DbMetrics.formatBytes(snapshot.javaMaxBytes)}  " +
                    "Native ${DbMetrics.formatBytes(snapshot.nativeHeapBytes)}  " +
                    "PSS ${DbMetrics.formatBytes(snapshot.totalPssBytes)}",
                color = Palette.Dim,
                style = TvType.caption
            )
            Text(
                text = "GC since start: ${snapshot.gcCount ?: "unavailable"} collections, " +
                    "${snapshot.gcTimeMs ?: "unavailable"} ms",
                color = Palette.Dim,
                style = TvType.caption
            )
            Spacer(Modifier.height(12.dp))
        }

        Row {
            Button(onClick = { DiagnosticLog.export(context, exportAppendix(metrics, runtime, catalogProfile)) }) {
                Text("Export log")
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = DiagnosticLog::clear) {
                Text("Clear log")
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                scope.launch {
                    catalogProfile = withContext(Dispatchers.IO) {
                        CatalogProfiler.profile(AppDatabase.get(context).openHelper.readableDatabase)
                    }
                }
            }) {
                Text("Profile catalog")
            }
        }
        Spacer(Modifier.height(20.dp))

        catalogProfile?.let { profile ->
            if (profile.samples.isEmpty()) {
                Text("No cached catalog is available to profile.", color = Palette.Dim, style = TvType.body)
            } else {
                Text("Catalog profile (local reads only)", color = Palette.Ink, style = TvType.title)
                profile.samples.forEach { sample ->
                    Text(
                        text = "${sample.label}: ${sample.elapsedMs} ms, ${sample.rowsRead} rows — ${sample.queryPlan}",
                        color = Palette.Dim,
                        style = TvType.caption
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (entries.isEmpty()) {
            // Not an error state: an empty log on a healthy app is the normal case.
            Text(text = "Nothing recorded yet.", color = Palette.Dim, style = TvType.body)
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries) { entry ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = entry.time,
                        color = Palette.Dim,
                        style = TvType.caption,
                        modifier = Modifier.width(88.dp)
                    )
                    Text(
                        text = entry.area,
                        color = Palette.Dim,
                        style = TvType.caption,
                        modifier = Modifier.width(120.dp)
                    )
                    Text(
                        text = entry.message,
                        color = when (entry.level) {
                            DiagnosticLog.Level.ERROR -> Palette.AccentText
                            DiagnosticLog.Level.WARN -> Palette.AccentText
                            DiagnosticLog.Level.INFO -> Palette.Ink
                        },
                        style = TvType.label
                    )
                }
            }
        }
    }
}

private fun exportAppendix(
    storage: DbMetrics.Snapshot?,
    runtime: RuntimeMetrics.Snapshot?,
    profile: CatalogProfiler.Snapshot?
): String = buildString {
    appendLine("Performance snapshot")
    storage?.let { snapshot ->
        appendLine(
            "Storage: ${DbMetrics.formatBytes(snapshot.totalBytes)} total; database " +
                "${DbMetrics.formatBytes(snapshot.databaseFootprintBytes)}; images " +
                DbMetrics.formatBytes(snapshot.imageCacheBytes)
        )
        if (snapshot.tableBytes.isNotEmpty()) {
            appendLine("Table pages: " + snapshot.tableBytes.entries.joinToString(", ") { (name, bytes) ->
                "$name ${DbMetrics.formatBytes(bytes)}"
            })
        }
    }
    runtime?.let { snapshot ->
        appendLine(
            "Memory: Java ${DbMetrics.formatBytes(snapshot.javaUsedBytes)} / " +
                "${DbMetrics.formatBytes(snapshot.javaMaxBytes)}; native " +
                "${DbMetrics.formatBytes(snapshot.nativeHeapBytes)}; PSS " +
                DbMetrics.formatBytes(snapshot.totalPssBytes)
        )
        appendLine("GC since start: ${snapshot.gcCount ?: "unavailable"} collections, ${snapshot.gcTimeMs ?: "unavailable"} ms")
    }
    profile?.let { snapshot ->
        appendLine("Catalog profile:")
        snapshot.samples.forEach { sample ->
            appendLine("${sample.label}: ${sample.elapsedMs} ms, ${sample.rowsRead} rows, ${sample.queryPlan}")
        }
    }
}
