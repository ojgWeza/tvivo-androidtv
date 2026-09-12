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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

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

        Row {
            Button(onClick = { DiagnosticLog.export(context) }) {
                Text("Export log")
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = DiagnosticLog::clear) {
                Text("Clear log")
            }
        }
        Spacer(Modifier.height(20.dp))

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
