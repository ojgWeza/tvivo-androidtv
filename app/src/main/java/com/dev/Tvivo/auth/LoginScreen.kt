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
        serverFocus.requestFocus()
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
            passwordFocus.requestFocus()
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

    // **D-1 — the TV layout is the fix, not the insets.**
    //
    // Everything focusable is positioned above the IME ceiling (y = 297 dp) by layout,
    // rather than scrolled into view after the fact. That distinction is the whole of
    // Q-11: a `verticalScroll` column only brings the *focused* child into view, so the
    // action row below it stayed under the keyboard no matter how correct the insets
    // were. There is no scroll here and no `imePadding()` — the content is ~290 dp tall,
    // top-anchored, and simply never reaches the keyboard.
    //
    // Q-28 adds a separate handset path below. Its smaller, stacked form is scrollable
    // and uses IME insets because a phone keyboard is not the TV IME ceiling problem.
    // `WindowCompat.setDecorFitsSystemWindows(window, false)` stays in `MainActivity`:
    // it is a precondition for insets working anywhere in the app, and it is what made
    // `imePadding()` stop being a silent no-op. It is just not what saves this screen.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isHandset) {
                    Modifier
                        // Edge-to-edge is required for Compose to observe IME insets, but
                        // a handset status bar must not overlay the wordmark or first field.
                        .safeDrawingPadding()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
            )
            .padding(
                horizontal = if (isHandset) HANDSET_HORIZONTAL_PADDING else 48.dp,
                vertical = if (isHandset) 16.dp else 24.dp
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
                    Text(text = "Tvivo", color = Palette.Ink, style = TvType.display)
                    Text(text = "Sign in to your provider", color = Palette.Dim, style = TvType.body)
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = "Tvivo", color = Palette.Ink, style = TvType.display)
                    Text(
                        text = "Sign in to your provider",
                        color = Palette.Dim,
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
                    label = { Text("Username", color = Palette.Dim) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrect = false,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
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
    focusedTextColor = Palette.Ink,
    unfocusedTextColor = Palette.Ink,
    focusedContainerColor = Palette.Elevated,
    unfocusedContainerColor = Palette.Elevated,
    cursorColor = Palette.Accent,
    focusedBorderColor = Palette.Accent,
    unfocusedBorderColor = Palette.Line
)
