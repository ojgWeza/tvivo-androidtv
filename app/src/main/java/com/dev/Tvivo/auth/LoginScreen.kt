package com.dev.Tvivo.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.ErrorCopy
import com.dev.Tvivo.ui.common.dpadFieldNavigation
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

/**
 * The first impression, on the worst input device. A realistic credential set is ~44
 * characters, which on a D-pad grid keyboard is 200+ directional presses — so the form
 * is never cleared, the password can be revealed, and the server is one field, not two.
 */
@Composable
fun LoginScreen(
    onAuthenticated: (AuthenticatedAccount) -> Unit,
    viewModel: LoginViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val serverFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    var passwordField by remember { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(Unit) {
        serverFocus.requestFocus()
        keyboard?.show()
    }

    LaunchedEffect(state.authenticated) {
        state.authenticated?.let(onAuthenticated)
    }

    // `Clear` lives in the ViewModel, but the password field holds its own
    // TextFieldValue for the select-all-on-reject behaviour, so it has to follow.
    LaunchedEffect(state.password) {
        if (state.password.isEmpty() && passwordField.text.isNotEmpty()) {
            passwordField = TextFieldValue("")
        }
    }

    // On a rejected login, keep every field, focus the password and select all of it so
    // overtyping replaces rather than appends.
    LaunchedEffect(state.refocusPassword) {
        if (state.refocusPassword) {
            passwordField = passwordField.copy(selection = TextRange(0, passwordField.text.length))
            passwordFocus.requestFocus()
            keyboard?.show()
        }
    }

    fun submit() {
        // The IME covers the button row and the error line. Leaving it open means the
        // user submits and then watches nothing happen.
        keyboard?.hide()
        viewModel.submit()
    }

    // **D-1 — the layout is the fix, not the insets.**
    //
    // Everything focusable is positioned above the IME ceiling (y = 297 dp) by layout,
    // rather than scrolled into view after the fact. That distinction is the whole of
    // Q-11: a `verticalScroll` column only brings the *focused* child into view, so the
    // action row below it stayed under the keyboard no matter how correct the insets
    // were. There is no scroll here and no `imePadding()` — the content is ~290 dp tall,
    // top-anchored, and simply never reaches the keyboard.
    //
    // `WindowCompat.setDecorFitsSystemWindows(window, false)` stays in `MainActivity`:
    // it is a precondition for insets working anywhere in the app, and it is what made
    // `imePadding()` stop being a silent no-op. It is just not what saves this screen.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        // Centred, not left-aligned against an empty right half.
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.width(FORM_WIDTH),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Wordmark and subtitle share a baseline rather than stacking. Stacking costs
            // 26 dp, and the budget to the ceiling is only a few dp wide once the fields
            // and the action row have taken their share.
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = "Tvivo", color = Palette.Ink, style = TvType.display)
                Text(
                    text = "Sign in to your provider",
                    color = Palette.Dim,
                    style = TvType.body,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                )
            }

            // Full-width row of its own: one field accepting `host:port`,
            // `http://host:port` or a bare host. Two fields would add a numeric keyboard
            // on TVs whose IME ignores `KeyboardType.Number`.
            androidx.compose.material3.OutlinedTextField(
                value = state.server,
                onValueChange = viewModel::onServerChanged,
                label = { Text("Server", color = Palette.Dim) },
                placeholder = { Text("host:port", color = Palette.Dim) },
                singleLine = true,
                isError = state.serverFieldError != null,
                keyboardOptions = KeyboardOptions(
                    // A server address is not prose; autocorrect would rewrite it.
                    keyboardType = KeyboardType.Uri,
                    autoCorrect = false,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = {
                    viewModel.onServerCommitted()
                    usernameFocus.requestFocus()
                }),
                colors = fieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(serverFocus)
                    .dpadFieldNavigation(focusManager)
            )

            // Username and password share one row. They are the two halves of a single
            // credential, and pairing them buys back a whole 56 dp row of ceiling budget.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChanged,
                    label = { Text("Username", color = Palette.Dim) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrect = false,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    colors = fieldColors(),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(usernameFocus)
                        .dpadFieldNavigation(focusManager)
                )

                androidx.compose.material3.OutlinedTextField(
                    value = passwordField,
                    onValueChange = {
                        passwordField = it
                        viewModel.onPasswordChanged(it.text)
                    },
                    label = { Text("Password", color = Palette.Dim) },
                    singleLine = true,
                    visualTransformation = if (state.showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        // Password type even when revealed: it stops the IME offering the
                        // password back as a suggestion and learning it into its
                        // dictionary.
                        keyboardType = KeyboardType.Password,
                        autoCorrect = false,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = fieldColors(),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(passwordFocus)
                        .dpadFieldNavigation(focusManager)
                )
            }

            // **The error line is always laid out, even when there is no error.** An
            // error that appears and pushes the action row down 22 dp pushes it toward
            // the keyboard at exactly the moment the user needs to press it. Reserving
            // the row costs nothing and keeps the ceiling arithmetic static.
            //
            // One line, hard: see `ErrorCopy.forLogin`. The server field's own validation
            // message shares this line rather than claiming a `supportingText` row under
            // the field, which would be a second variable-height element.
            Box(modifier = Modifier.fillMaxWidth().height(ERROR_LINE_HEIGHT)) {
                val message = state.error?.let { ErrorCopy.forLogin(it).message }
                    ?: state.serverFieldError
                if (message != null) {
                    Text(
                        text = message,
                        color = Palette.AccentText,
                        style = TvType.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // One action row, centred. `Show` moves here from beside the password field:
            // Q-6 put it there so it sat next to what it controls, but it was never
            // reachable while the IME was up either way (Q-10 — the keyboard owns the
            // D-pad), and one row of three uniform actions is what the comps specify.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The keyboard has nothing to edit once focus has left every field,
                    // and dismissing it here is what makes this row usable at all.
                    .onFocusChanged { if (it.hasFocus) keyboard?.hide() },
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                Button(
                    onClick = { submit() },
                    enabled = state.canSubmit,
                    modifier = Modifier.tvFocusFrame()
                ) {
                    Text(if (state.isSubmitting) "Signing in…" else "Sign in")
                }
                Button(
                    onClick = viewModel::onTogglePasswordVisibility,
                    modifier = Modifier.tvFocusFrame()
                ) {
                    Text(if (state.showPassword) "Hide password" else "Show password")
                }
                Button(
                    onClick = viewModel::onClear,
                    modifier = Modifier.tvFocusFrame()
                ) {
                    Text("Clear")
                }
            }
        }
    }
}

/** 820 px at 1 dp = 2 px. */
private val FORM_WIDTH = 410.dp

/** One line of `body`, reserved whether or not there is an error to put in it. */
private val ERROR_LINE_HEIGHT = 22.dp

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.Ink,
    unfocusedTextColor = Palette.Ink,
    focusedContainerColor = Palette.Elevated,
    unfocusedContainerColor = Palette.Elevated,
    cursorColor = Palette.Accent,
    focusedBorderColor = Palette.Accent,
    unfocusedBorderColor = Palette.Line
)
