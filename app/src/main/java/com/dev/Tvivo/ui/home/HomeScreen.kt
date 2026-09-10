package com.dev.Tvivo.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.dev.Tvivo.R
import com.dev.Tvivo.ui.common.ConfirmDialog
import com.dev.Tvivo.ui.common.IconPill
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

enum class ContentType(val label: String) {
    LIVE("Live TV"),
    MOVIES("Movies"),
    SERIES("Series");

    /**
     * D-9. Stored at `drawable-nodpi` and downloaded at exactly 520x300 — the tile size —
     * so nothing is rescaled at runtime. `nodpi` is the point: any density bucket would
     * have Android scale the bitmap for the device's density and undo that.
     *
     * The three read as different *ideas* rather than three pictures of screens:
     * broadcast, the big screen, episodes in sequence. All three were screened for
     * third-party brand marks — see `docs/design/img/CREDITS.md`, where two otherwise
     * good candidates were rejected for a visible Netflix logo.
     */
    @get:DrawableRes
    val art: Int
        get() = when (this) {
            LIVE -> R.drawable.tile_live
            MOVIES -> R.drawable.tile_movies
            SERIES -> R.drawable.tile_series
        }
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

    // **Q-17 — the pill row's traversal is declared, not inferred.**
    //
    // Compose's 2D focus search picks by geometry, and once was observed sending RIGHT
    // from `Refresh` *down* to the Series tile rather than across to `Account`, leaving
    // Account and Exit reachable only by accident. `animateContentSize` is the likely
    // reason it is intermittent: a pill's bounds change while it expands, so a press
    // landing mid-animation is scored against bounds that no longer hold.
    //
    // The rail needed exactly this treatment for exactly this reason. On this project the
    // 2D search is overridden, not trusted.
    val refreshPill = remember { FocusRequester() }
    val accountPill = remember { FocusRequester() }
    val exitPill = remember { FocusRequester() }

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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.focusGroup()
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
                    icon = Icons.Default.Refresh,
                    label = if (isRefreshing) "Refreshing…" else "Refresh all",
                    // Guarded in the ViewModel too, but a disabled pill says why nothing
                    // happens on a second press instead of silently swallowing it.
                    enabled = !isRefreshing,
                    onClick = onRefreshEverything,
                    modifier = Modifier
                        .focusRequester(refreshPill)
                        .focusProperties { right = accountPill }
                )
                IconPill(
                    icon = Icons.Default.AccountCircle,
                    label = "Account",
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .focusRequester(accountPill)
                        .focusProperties {
                            left = refreshPill
                            right = exitPill
                        }
                )
                // Last in the row and behind a confirm: on a household remote this is one
                // press from the first screen, and quitting is not something to do by
                // accident. See `TODOS.md` Part 2b — decided 2026-09-08.
                IconPill(
                    icon = Icons.Default.ExitToApp,
                    label = "Exit",
                    onClick = { confirmingExit = true },
                    modifier = Modifier
                        .focusRequester(exitPill)
                        .focusProperties { left = accountPill }
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
                    art = type.art,
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
private fun HomeTile(
    label: String,
    @DrawableRes art: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = Palette.Elevated),
        // tv-material3 draws its own grey focus outline. Left on, the tile shows two
        // competing rings; the app has exactly one focus indicator (Q-3).
        border = CardDefaults.border(focusedBorder = Border.None),
        modifier = modifier.height(180.dp).tvFocusFrame()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(art),
                // The label below says what this is; announcing the photograph as well
                // would have a screen reader read the tile twice.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Bottom-up scrim. The label has to stay legible over three photographs that
            // were not chosen for their bottom-edge luminance, so the scrim does the work
            // rather than the crop.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Palette.Bg.copy(alpha = 0.35f),
                            1f to Palette.Bg.copy(alpha = 0.92f)
                        )
                    )
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(text = label, color = Palette.Ink, style = TvType.headline)
            }
        }
    }
}
