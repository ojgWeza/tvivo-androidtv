package com.dev.Tvivo.auth

import android.content.res.Configuration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import com.dev.Tvivo.AppIdentity

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
    val configuration = LocalConfiguration.current
    val isTelevision = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION
    // The TV form is a deliberately fixed ten-foot layout. A compact non-TV device is
    // different input hardware and a different viewport, not a smaller TV.
    val isHandset = !isTelevision && configuration.smallestScreenWidthDp < HANDSET_BREAKPOINT_DP
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    val serverFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    var passwordField by remember { mutableStateOf(TextFieldValue("")) }
    var hadVisibleIme by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { serverFocus.requestFocus() }
        // A TV remote needs an explicit invitation to type. On a handset, opening the
        // keyboard before the user taps a field obscures the form for no benefit.
        if (isTelevision) keyboard?.show()
    }

    // On a handset, Back after typing must not leave an invisible text-field focus owner
    // behind. Do not apply this to TV: after its IME closes the D-pad needs the focused
    // field so `dpadFieldNavigation` can move out of it.
    LaunchedEffect(imeVisible, isHandset) {
        if (isHandset && hadVisibleIme && !imeVisible) focusManager.clearFocus(force = true)
        hadVisibleIme = imeVisible
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
            runCatching { passwordFocus.requestFocus() }
            keyboard?.show()
        }
    }

    fun submit() {
        // The IME covers the button row and the error line. Leaving it open means the
        // user submits and then watches nothing happen.
        keyboard?.hide()
        DiagnosticLog.info("navigation", "Login sign-in requested")
        viewModel.submit()
    }

    // The form can scroll when the IME takes space, while imePadding keeps the action
    // row in the remaining viewport. The TV content also stays within the measured
    // 297 dp focus ceiling; the handset keeps its safe drawing inset.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (isHandset) Modifier.safeDrawingPadding() else Modifier)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = if (isHandset) HANDSET_HORIZONTAL_PADDING else 48.dp,
                vertical = 16.dp
            ),
        // Centred, not left-aligned against an empty right half.
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = if (isHandset) Modifier.fillMaxWidth() else Modifier.width(FORM_WIDTH),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Wordmark and subtitle share a baseline rather than stacking. Stacking costs
            // 26 dp, and the budget to the ceiling is only a few dp wide once the fields
            // and the action row have taken their share.
            if (isHandset) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = AppIdentity.name(), color = AppColors.Ink, style = TvType.display)
                    Text(text = "Sign in to your provider", color = AppColors.Dim, style = TvType.body)
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = AppIdentity.name(), color = AppColors.Ink, style = TvType.display)
                    Text(
                        text = "Sign in to your provider",
                        color = AppColors.Dim,
                        style = TvType.body,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )
                }
            }

            // Full-width row of its own: one field accepting `host:port`,
            // `http://host:port` or a bare host. Two fields would add a numeric keyboard
            // on TVs whose IME ignores `KeyboardType.Number`.
            androidx.compose.material3.OutlinedTextField(
                value = state.server,
                onValueChange = viewModel::onServerChanged,
                label = { Text("Server", color = AppColors.Dim) },
                placeholder = { Text("host:port", color = AppColors.Dim) },
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
                    runCatching { usernameFocus.requestFocus() }
                }),
                colors = fieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(serverFocus)
                    .onFocusChanged {
                        if (it.isFocused) DiagnosticLog.info("focus", "Login server field focused")
                    }
                    .dpadFieldNavigation(focusManager)
            )

            // The TV pairs credentials to stay above its IME ceiling. Handsets stack them
            // so each field remains comfortably tappable at compact widths.
            val credentialsModifier = if (isHandset) Modifier.fillMaxWidth() else Modifier.weight(1f)
            val credentialsContainer: @Composable (@Composable () -> Unit) -> Unit = { content ->
                if (isHandset) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) { content() }
                }
            }
            credentialsContainer {
                androidx.compose.material3.OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChanged,
                    label = { Text("Username", color = AppColors.Dim) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrect = false,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { runCatching { passwordFocus.requestFocus() } }),
                    colors = fieldColors(),
                    modifier = credentialsModifier
                        .focusRequester(usernameFocus)
                        .onFocusChanged {
                            if (it.isFocused) DiagnosticLog.info("focus", "Login username field focused")
                        }
                        .dpadFieldNavigation(focusManager)
                )

                androidx.compose.material3.OutlinedTextField(
                    value = passwordField,
                    onValueChange = {
                        passwordField = it
                        viewModel.onPasswordChanged(it.text)
                    },
                    label = { Text("Password", color = AppColors.Dim) },
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
                    modifier = credentialsModifier
                        .focusRequester(passwordFocus)
                        .onFocusChanged {
                            if (it.isFocused) DiagnosticLog.info("focus", "Login password field focused")
                        }
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
                        color = AppColors.Error,
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
                    onClick = {
                        DiagnosticLog.info("interaction", "Login password visibility toggled")
                        viewModel.onTogglePasswordVisibility()
                    },
                    modifier = Modifier.tvFocusFrame()
                ) {
                    Text(
                        if (state.showPassword) {
                            if (isHandset) "Hide" else "Hide password"
                        } else {
                            if (isHandset) "Show" else "Show password"
                        }
                    )
                }
                Button(
                    onClick = {
                        DiagnosticLog.info("interaction", "Login credentials cleared")
                        viewModel.onClear()
                    },
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

/** Handsets use their available width; tablets retain the established wide form. */
private const val HANDSET_BREAKPOINT_DP = 600
private val HANDSET_HORIZONTAL_PADDING = 24.dp

/** One line of `body`, reserved whether or not there is an error to put in it. */
private val ERROR_LINE_HEIGHT = 22.dp

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AppColors.Ink,
    unfocusedTextColor = AppColors.Ink,
    focusedContainerColor = AppColors.Elevated,
    unfocusedContainerColor = AppColors.Elevated,
    cursorColor = AppColors.Accent,
    focusedBorderColor = AppColors.AccentSoft,
    unfocusedBorderColor = AppColors.Line
)
