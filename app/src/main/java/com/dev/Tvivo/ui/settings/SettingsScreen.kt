package com.dev.Tvivo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.ConfirmDialog
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

/**
 * Account **actions**, and only actions.
 *
 * **D-17 — Refresh and Exit are gone from here.** They are global actions and live in the
 * Home icon row: refreshing the catalog is not account business, and neither is quitting.
 * **D-15 — the subscription facts are gone too**, onto their own read-only screen behind
 * `Show subscription`. What is left is the three things that are genuinely about *this
 * account*.
 *
 * `Sign in to a different account` is also the only non-destructive route back to the
 * login screen, which is what makes the login screen testable at all without clearing app
 * data.
 */
@Composable
fun SettingsScreen(
    onSignedOut: () -> Unit,
    onSwitchAccount: () -> Unit,
    onShowSubscription: () -> Unit,
    onShowDiagnostics: () -> Unit,
    viewModel: AccountViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstAction = remember { FocusRequester() }
    var confirmingSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { firstAction.requestFocus() }
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = 96.dp, vertical = 64.dp)
    ) {
        Text(text = "Account", color = Palette.Ink, style = TvType.display)
        state.username?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, color = Palette.Dim, style = TvType.body)
        }
        Spacer(Modifier.height(32.dp))

        ActionRow(
            label = "Show subscription",
            hint = "Status, expiry and connections, as this panel reports them",
            modifier = Modifier.focusRequester(firstAction),
            onClick = onShowSubscription
        )
        ActionRow(
            label = "Sign in to a different account",
            hint = "Keeps this account signed in until the new one is accepted",
            onClick = onSwitchAccount
        )
        ActionRow(
            label = "Diagnostics",
            hint = "What the app has done since it started, for when something looks wrong",
            onClick = onShowDiagnostics
        )

        // **D-12 — everything reversible is above this line.**
        //
        // Sign out is separated by a divider and is last, because it is the only thing on
        // this screen that cannot be undone. Grouping it with the switch action made two
        // very different consequences look like two neighbouring menu items, and the two
        // are one row apart on a D-pad.
        Spacer(Modifier.height(28.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Palette.Line)
        ) {}
        Spacer(Modifier.height(20.dp))

        ActionRow(
            label = "Sign out",
            // The consequence, not the mechanism. "Forgets the stored credentials" reads
            // like a preference being reset; this says what the user actually loses.
            hint = "Erases the saved credentials from this device. They cannot be recovered.",
            destructive = true,
            onClick = { confirmingSignOut = true }
        )
    }

    if (confirmingSignOut) {
        ConfirmDialog(
            title = "Sign out of ${state.username ?: "this account"}?",
            // The Tink keyset is not exportable, so this is not recoverable from a backup
            // either — the only way back is re-typing ~44 characters on a D-pad.
            consequence = "The saved credentials are erased from this device and cannot " +
                "be recovered. You would have to type the server, username and password " +
                "in again on the remote.",
            confirmLabel = "Sign out",
            safeLabel = "Keep me signed in",
            onConfirm = {
                confirmingSignOut = false
                viewModel.signOut()
            },
            onDismiss = { confirmingSignOut = false }
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusFrame()
            .tvClickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = label,
            color = if (destructive) Palette.AccentText else Palette.Ink,
            style = TvType.title
        )
        Text(text = hint, color = Palette.Dim, style = TvType.label)
    }
}
