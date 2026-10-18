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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.ErrorCopy
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.adaptiveSpacing

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
    val spacing = adaptiveSpacing()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
            .padding(
                horizontal = if (LocalIsTelevision.current) 96.dp else spacing.outerGutter,
                vertical = if (LocalIsTelevision.current) 64.dp else 16.dp
            )
    ) {
        Text(text = stringResource(com.dev.Tvivo.R.string.subscription_title), color = AppColors.Ink, style = TvType.display)
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Elevated)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FactRow(stringResource(com.dev.Tvivo.R.string.subscription_signed_in_as), state.username)
            FactRow(stringResource(com.dev.Tvivo.R.string.subscription_server), state.server)
            FactRow(stringResource(com.dev.Tvivo.R.string.subscription_status), state.status?.let {
                if (state.isTrial) stringResource(com.dev.Tvivo.R.string.subscription_trial_status, it) else it
            })
            FactRow(
                label = stringResource(com.dev.Tvivo.R.string.subscription_expiry),
                value = state.expiry,
                // The one thing worth colouring: an expiry inside a week is the
                // difference between "working" and "about to stop".
                highlight = state.expiringSoon
            )
            FactRow(stringResource(com.dev.Tvivo.R.string.subscription_connections), state.connections)

            if (state.isLoading) {
                Text(text = stringResource(com.dev.Tvivo.R.string.subscription_checking), color = AppColors.Dim, style = TvType.label)
            }
            state.error?.let {
                // Offline is not signed out. The facts above are the last known ones and
                // stay on screen; this only says the live check did not get through.
                Text(
                    text = ErrorCopy.of(it).message,
                    color = AppColors.AccentText,
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
            color = AppColors.Dim,
            style = TvType.body
        )

        Spacer(Modifier.height(12.dp))
        Text(text = "Press Back to return to Account.", color = AppColors.Dim, style = TvType.label)
    }
}

@Composable
private fun FactRow(label: String, value: String?, highlight: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = AppColors.Dim,
            style = TvType.body,
            modifier = Modifier.width(200.dp)
        )
        Text(
            text = value ?: "—",
            color = if (highlight) AppColors.AccentText else AppColors.Ink,
            style = TvType.body
        )
    }
}
