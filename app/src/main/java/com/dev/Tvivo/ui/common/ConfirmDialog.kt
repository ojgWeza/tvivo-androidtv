package com.dev.Tvivo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

/**
 * The one confirm treatment, for anything irreversible or irreversible-feeling.
 *
 * **Default focus is always on the safe option, never the destructive one.** On a remote,
 * the most likely next press after a dialog appears is OK — whatever is focused is what a
 * user gets by reflex, so the reflex has to be harmless. [safeLabel] is therefore first
 * and pre-focused, and [confirmLabel] has to be travelled to.
 *
 * Back dismisses, which is also safe: `Dialog` routes it to [onDismiss].
 *
 * [consequence] states **what happens**, not what the mechanism is. "Erases the saved
 * credentials from this device. They cannot be recovered." tells the user what they lose;
 * "Calls store.wipe()" tells them nothing they can act on.
 */
@Composable
fun ConfirmDialog(
    title: String,
    consequence: String,
    confirmLabel: String,
    safeLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val safeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { safeFocus.requestFocus() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(Palette.Elevated)
                .padding(28.dp)
        ) {
            Text(text = title, color = Palette.Ink, style = TvType.title)
            Spacer(Modifier.height(8.dp))
            Text(text = consequence, color = Palette.Dim, style = TvType.body)
            Spacer(Modifier.height(20.dp))

            DialogAction(
                label = safeLabel,
                onClick = onDismiss,
                modifier = Modifier.focusRequester(safeFocus)
            )
            DialogAction(label = confirmLabel, onClick = onConfirm)
        }
    }
}

@Composable
private fun DialogAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = label,
        color = Palette.Ink,
        style = TvType.title,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusFrame()
            .tvClickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    )
}
