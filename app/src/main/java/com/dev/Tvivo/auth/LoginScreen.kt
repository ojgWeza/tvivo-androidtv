package com.dev.Tvivo.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.ErrorCopy
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.Palette

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

    // The TV IME is a bottom-anchored panel that covers roughly the lower half of a
    // 1080p screen. Left-aligned and scrollable keeps every field reachable: Compose
    // brings a focused field into view rather than leaving it under the keyboard.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 32.dp)
            .imePadding(),
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Tvivo", color = Palette.Ink, fontSize = 32.sp)
            Text(
                text = "Sign in to your provider",
                color = Palette.Dim,
                fontSize = 16.sp
            )

            Spacer(Modifier.height(4.dp))

            androidx.compose.material3.OutlinedTextField(
                value = state.server,
                onValueChange = viewModel::onServerChanged,
                label = { Text("Server", color = Palette.Dim) },
                placeholder = { Text("host:port", color = Palette.Dim) },
                singleLine = true,
                isError = state.serverFieldError != null,
                supportingText = state.serverFieldError?.let {
                    { Text(it, color = Palette.AccentText) }
                },
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
                    .focusRequester(serverFocus)
                    .dpadFieldNavigation(focusManager)
            )

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
                    // password back as a suggestion and learning it into its dictionary.
                    keyboardType = KeyboardType.Password,
                    autoCorrect = false,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = fieldColors(),
                modifier = Modifier
                    .focusRequester(passwordFocus)
                    .dpadFieldNavigation(focusManager)
            )

            // Above the buttons, not below them: an error under the fold is an error the
            // user never sees.
            state.error?.let { error ->
                val copy = ErrorCopy.of(error)
                Text(text = copy.message, color = Palette.AccentText, fontSize = 16.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = viewModel::onTogglePasswordVisibility,
                    modifier = Modifier.tvFocusFrame()
                ) {
                    Text(if (state.showPassword) "Hide password" else "Show password")
                }
                Button(
                    onClick = { submit() },
                    enabled = state.canSubmit,
                    modifier = Modifier.tvFocusFrame()
                ) {
                    Text(if (state.isSubmitting) "Signing in…" else "Sign in")
                }
            }
        }
    }
}

/**
 * Without this the Sign in button is unreachable and the app is unusable on a remote.
 *
 * A Compose text field swallows D-pad up/down to move its own cursor, which on a phone is
 * right and on a TV is fatal — it is the only way out of the field. Intercepting in the
 * *preview* pass moves focus before the field ever sees the event.
 */
private fun Modifier.dpadFieldNavigation(focusManager: FocusManager): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> {
                focusManager.moveFocus(FocusDirection.Down)
                true
            }
            Key.DirectionUp -> {
                focusManager.moveFocus(FocusDirection.Up)
                true
            }
            else -> false
        }
    }

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
