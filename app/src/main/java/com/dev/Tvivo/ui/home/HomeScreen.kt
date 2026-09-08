package com.dev.Tvivo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.ConfirmDialog
import com.dev.Tvivo.ui.common.IconPill
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

enum class ContentType(val label: String) {
    LIVE("Live TV"),
    MOVIES("Movies"),
    SERIES("Series")
}

/**
 * Three destinations, plus the global action row. Search is scoped by content type, so
 * the choice made here is what makes the search boundary visible in navigation rather
 * than in a label the user has to read.
 *
 * [accountSummary] is the account and its expiry on one line. It sits on the first
 * screen after login on purpose: a lapsed subscription otherwise presents as "every
 * stream is broken", which is a much longer thing to work out.
 *
 * **D-7/D-17 — Refresh and Exit live here, not on the Account screen.** Refreshing the
 * catalog is not account business and neither is quitting the app; both are global, so
 * both sit in the global row.
 */
@Composable
fun HomeScreen(
    lastSelected: ContentType?,
    accountSummary: String?,
    accountWarning: Boolean,
    isRefreshing: Boolean,
    refreshMessage: String?,
    onRefreshEverything: () -> Unit,
    onSelect: (ContentType) -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit
) {
    // Focus lands on the tile the user last opened, so Back out of Browse returns them
    // where they were rather than resetting to the left edge every time.
    val restore = remember { FocusRequester() }
    val focusTarget = lastSelected ?: ContentType.LIVE
    LaunchedEffect(Unit) { restore.requestFocus() }

    var confirmingExit by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 64.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Tvivo", color = Palette.Ink, style = TvType.display)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                accountSummary?.let {
                    Text(
                        text = it,
                        color = if (accountWarning) Palette.AccentText else Palette.Dim,
                        style = TvType.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                IconPill(
                    glyph = "↻",
                    label = if (isRefreshing) "Refreshing…" else "Refresh everything",
                    // Guarded in the ViewModel too, but a disabled pill says why nothing
                    // happens on a second press instead of silently swallowing it.
                    enabled = !isRefreshing,
                    onClick = onRefreshEverything
                )
                IconPill(
                    glyph = "◔",
                    label = "Account",
                    onClick = onOpenSettings
                )
                // Last in the row and behind a confirm: on a household remote this is one
                // press from the first screen, and quitting is not something to do by
                // accident. See `TODOS.md` Part 2b — decided 2026-09-08.
                IconPill(
                    glyph = "⏻",
                    label = "Exit",
                    onClick = { confirmingExit = true }
                )
            }
        }

        // Refresh reports progress inline and never blocks the screen — the catalogs are
        // ~68k rows and the user can keep browsing throughout.
        refreshMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(text = it, color = Palette.Dim, style = TvType.body)
        }

        Spacer(Modifier.height(48.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            ContentType.entries.forEach { type ->
                HomeTile(
                    label = type.label,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (type == focusTarget) Modifier.focusRequester(restore) else Modifier),
                    onClick = { onSelect(type) }
                )
            }
        }
    }

    if (confirmingExit) {
        ConfirmDialog(
            title = "Exit Tvivo?",
            consequence = "Closes the app and returns to the launcher. " +
                "Nothing is signed out and nothing cached is lost.",
            confirmLabel = "Exit",
            safeLabel = "Stay in Tvivo",
            onConfirm = {
                confirmingExit = false
                onExit()
            },
            onDismiss = { confirmingExit = false }
        )
    }
}

@Composable
private fun HomeTile(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = Palette.Elevated),
        // tv-material3 draws its own grey focus outline. Left on, the tile shows two
        // competing rings; the app has exactly one focus indicator (Q-3).
        border = CardDefaults.border(focusedBorder = Border.None),
        modifier = modifier.height(180.dp).tvFocusFrame()
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(text = label, color = Palette.Ink, style = TvType.headline)
        }
    }
}
