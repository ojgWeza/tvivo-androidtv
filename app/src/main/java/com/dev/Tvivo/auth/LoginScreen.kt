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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = {
                    viewModel.onServerCommitted()
                    usernameFocus.requestFocus()
                }),
                colors = fieldColors(),
                modifier = Modifier.focusRequester(serverFocus)
            )

            androidx.compose.material3.OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChanged,
                label = { Text("Username", color = Palette.Dim) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                colors = fieldColors(),
                modifier = Modifier.focusRequester(usernameFocus)
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                colors = fieldColors(),
                modifier = Modifier.focusRequester(passwordFocus)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = viewModel::onTogglePasswordVisibility) {
                    Text(if (state.showPassword) "Hide password" else "Show password")
                }
                Button(
                    onClick = viewModel::submit,
                    enabled = state.canSubmit
                ) {
                    Text(if (state.isSubmitting) "Signing in…" else "Sign in")
                }
            }

            state.error?.let { error ->
                val copy = ErrorCopy.of(error)
                Spacer(Modifier.height(8.dp))
                Text(text = copy.message, color = Palette.AccentText, fontSize = 18.sp)
            }
        }
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
