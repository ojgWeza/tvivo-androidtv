package com.dev.Tvivo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.ErrorCopy
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.Palette

/**
 * Account and whole-app actions. Everything here was previously unreachable: there was
 * no way to sign out, no way to see when the subscription ends, no way to refresh
 * anything but the category currently on screen, and no way out of the app but the
 * launcher key.
 *
 * Signing out is also the only non-destructive route back to the login screen, which
 * is what makes the login screen testable at all without clearing app data.
 */
@Composable
fun SettingsScreen(
    onSignedOut: () -> Unit,
    onSwitchAccount: () -> Unit,
    onExit: () -> Unit,
    viewModel: AccountViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstAction = remember { FocusRequester() }

    LaunchedEffect(Unit) { firstAction.requestFocus() }
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = 96.dp, vertical = 64.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Account", color = Palette.Ink, fontSize = 32.sp)
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Elevated)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DetailRow("Signed in as", state.username)
            DetailRow("Server", state.server)
            DetailRow("Status", state.status?.let { if (state.isTrial) "$it (trial)" else it })
            DetailRow(
                label = "Subscription",
                value = state.expiry,
                // The one thing on this screen worth colouring: an expiry inside a week
                // is the difference between "working" and "about to stop".
                highlight = state.expiringSoon
            )
            // max_connections is 1 on this account, which is why a second device
            // playing makes this one fail. Worth surfacing rather than debugging twice.
            DetailRow("Connections", state.connections)

            if (state.isLoading) {
                Text(text = "Checking with the panel…", color = Palette.Dim, fontSize = 14.sp)
            }
            state.error?.let {
                Text(
                    text = ErrorCopy.of(it).message,
                    color = Palette.AccentText,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        state.refreshMessage?.let {
            Text(text = it, color = Palette.Dim, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
        }

        ActionRow(
            label = if (state.isRefreshing) "Refreshing everything…" else "Refresh everything",
            hint = "Re-fetches every category and both catalogs, ignoring the 24h cache",
            modifier = Modifier.focusRequester(firstAction),
            onClick = { viewModel.refreshEverything() }
        )
        ActionRow(
            label = "Sign in to a different account",
            hint = "Keeps this account signed in until the new one is accepted",
            onClick = onSwitchAccount
        )
        ActionRow(
            label = "Sign out",
            hint = "Forgets the stored credentials on this device",
            onClick = { viewModel.signOut() }
        )
        ActionRow(
            label = "Exit Tvivo",
            hint = "Closes the app and returns to the launcher",
            onClick = onExit
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String?, highlight: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Palette.Dim,
            fontSize = 16.sp,
            modifier = Modifier.width(200.dp)
        )
        Text(
            text = value ?: "—",
            color = if (highlight) Palette.AccentText else Palette.Ink,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusFrame()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(text = label, color = Palette.Ink, fontSize = 20.sp)
        Text(text = hint, color = Palette.Dim, fontSize = 14.sp)
    }
}
