package com.dev.Tvivo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.ErrorCopy
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

/**
 * D-15 — the subscription facts, on their own screen, **read-only**.
 *
 * Nothing here is focusable and nothing here is an action: the account screen keeps the
 * account *actions*, and this keeps the account *facts*. Mixing them made the Account
 * screen a list where "when does this expire" and "erase my credentials" sat in the same
 * visual rank.
 *
 * **D-16 — this reports `max_connections`, it never asserts a limit.** The value is an
 * account property read from `server_info`; it is `1` on the development account and
 * differs on other panels. Copy that says "you can only watch on one device" would be
 * stating a product rule the app does not have, and would be wrong on the first panel
 * that allows three.
 */
@Composable
fun SubscriptionScreen(viewModel: AccountViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = 96.dp, vertical = 64.dp)
    ) {
        Text(text = "Subscription", color = Palette.Ink, style = TvType.display)
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Elevated)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FactRow("Signed in as", state.username)
            FactRow("Server", state.server)
            FactRow("Status", state.status?.let { if (state.isTrial) "$it (trial)" else it })
            FactRow(
                label = "Expiry",
                value = state.expiry,
                // The one thing worth colouring: an expiry inside a week is the
                // difference between "working" and "about to stop".
                highlight = state.expiringSoon
            )
            FactRow("Connections", state.connections)

            if (state.isLoading) {
                Text(text = "Checking with the panel…", color = Palette.Dim, style = TvType.label)
            }
            state.error?.let {
                // Offline is not signed out. The facts above are the last known ones and
                // stay on screen; this only says the live check did not get through.
                Text(
                    text = ErrorCopy.of(it).message,
                    color = Palette.AccentText,
                    style = TvType.label
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Reports the number, and stops. `max_connections` is what the panel says about
        // this account, not a rule this app enforces or can promise.
        Text(
            text = "Connections is what this panel reports for this account. " +
                "If a stream fails while another device is watching, that is usually why.",
            color = Palette.Dim,
            style = TvType.body
        )

        Spacer(Modifier.height(12.dp))
        Text(text = "Press Back to return to Account.", color = Palette.Dim, style = TvType.label)
    }
}

@Composable
private fun FactRow(label: String, value: String?, highlight: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Palette.Dim,
            style = TvType.body,
            modifier = Modifier.width(200.dp)
        )
        Text(
            text = value ?: "—",
            color = if (highlight) Palette.AccentText else Palette.Ink,
            style = TvType.body
        )
    }
}
