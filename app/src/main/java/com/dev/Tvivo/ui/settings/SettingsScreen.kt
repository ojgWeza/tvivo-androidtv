package com.dev.Tvivo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.ConfirmDialog
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import com.dev.Tvivo.ui.shell.LocalShellFocus
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.adaptiveSpacing
import com.dev.Tvivo.playback.PlayerPreferencesStore
import com.dev.Tvivo.playback.PlaybackMode
import com.dev.Tvivo.playback.PreferredAudio
import com.dev.Tvivo.playback.PreferredSubtitles
import com.dev.Tvivo.ui.ThemeMode
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Account **actions**, and only actions.
 *
 * Refresh library lives here with the last completed catalog update. Exit is handled by
 * Back from My Tvivo. Subscription facts live on their own read-only screen behind
 * `Show subscription`.
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
    onRefreshLibrary: () -> Unit = {},
    viewModel: AccountViewModel? = null,
    stateOverride: AccountUiState? = null,
    onConfirmSignOutOverride: () -> Unit = {}
) {
    val activeViewModel = if (stateOverride == null) {
        viewModel ?: androidx.lifecycle.viewmodel.compose.viewModel()
    } else null
    val state = if (stateOverride != null) stateOverride
        else requireNotNull(activeViewModel).state.collectAsStateWithLifecycle().value
    val firstAction = LocalShellFocus.current?.firstContent ?: remember { FocusRequester() }
    var confirmingSignOut by remember { mutableStateOf(false) }
    val appContext = LocalContext.current
    val preferenceStore = remember(appContext) { PlayerPreferencesStore(appContext) }
    val scope = rememberCoroutineScope()
    val modes by preferenceStore.modes.collectAsStateWithLifecycle(initialValue = com.dev.Tvivo.playback.PlaybackModePreferences.AndroidDefaults)
    val tracks by preferenceStore.tracks.collectAsStateWithLifecycle(initialValue = com.dev.Tvivo.playback.TrackPreferences.AndroidDefaults)
    val themeMode by preferenceStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.Dark)
    val spacing = adaptiveSpacing()

    LaunchedEffect(Unit) { runCatching { firstAction.requestFocus() } }
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }

    // **Q-23 — this Column must scroll.** Its content is 451 dp against 412 dp of
    // usable height (540 dp screen less 128 dp of vertical padding), and an unscrollable
    // Column does not overflow: it squeezes the *last* child. The last child here is
    // Sign out, so the one irreversible action on the screen was measured down to 48 dp
    // and rendered as an empty focus frame — focusable, reachable, and completely
    // unlabelled. Scrolling gives every row the height it asked for, and TV focus
    // traversal brings the row into view on its own.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
            .then(if (LocalIsTelevision.current) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = if (LocalIsTelevision.current) 96.dp else spacing.outerGutter,
                vertical = if (LocalIsTelevision.current) 64.dp else 16.dp
            )
    ) {
        Text(text = "Account", color = AppColors.Ink, style = TvType.display)
        Spacer(Modifier.height(24.dp))
        Text(text = "Player", color = AppColors.Ink, style = TvType.title)
        Text(text = stringResource(com.dev.Tvivo.R.string.appearance), color = AppColors.Ink, style = TvType.title)
        ActionRow(stringResource(com.dev.Tvivo.R.string.theme), stringResource(com.dev.Tvivo.R.string.theme_hint), value = stringResource(if (themeMode == ThemeMode.Dark) com.dev.Tvivo.R.string.theme_dark else com.dev.Tvivo.R.string.theme_light), onClick = {
            scope.launch { preferenceStore.setThemeMode(if (themeMode == ThemeMode.Dark) ThemeMode.Light else ThemeMode.Dark) }
        })
        ActionRow("Episodes", "Play next mode · Off, Next or Shuffle", value = modes.episodeMode.name, onClick = {
            val next = when (modes.episodeMode) { PlaybackMode.Off -> PlaybackMode.Next; PlaybackMode.Next -> PlaybackMode.Shuffle; PlaybackMode.Shuffle -> PlaybackMode.Off }
            scope.launch { preferenceStore.setEpisodeMode(next) }
        })
        ActionRow("Movies", "Play next mode · Off, Next or Shuffle", value = modes.movieMode.name, onClick = {
            val next = when (modes.movieMode) { PlaybackMode.Off -> PlaybackMode.Next; PlaybackMode.Next -> PlaybackMode.Shuffle; PlaybackMode.Shuffle -> PlaybackMode.Off }
            scope.launch { preferenceStore.setMovieMode(next) }
        })
        ActionRow("Preferred audio", "Auto, Arabic or English", value = tracks.audio.name, onClick = {
            val next = when (tracks.audio) { PreferredAudio.Auto -> PreferredAudio.Arabic; PreferredAudio.Arabic -> PreferredAudio.English; PreferredAudio.English -> PreferredAudio.Auto }
            scope.launch { preferenceStore.setAudio(next) }
        })
        ActionRow("Preferred subtitles", "Off, Arabic or English", value = tracks.subtitles.name, onClick = {
            val next = when (tracks.subtitles) { PreferredSubtitles.Off -> PreferredSubtitles.Arabic; PreferredSubtitles.Arabic -> PreferredSubtitles.English; PreferredSubtitles.English -> PreferredSubtitles.Off }
            scope.launch { preferenceStore.setSubtitles(next) }
        })
        state.username?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, color = AppColors.Dim, style = TvType.body)
        }
        Spacer(Modifier.height(32.dp))

        ActionRow(
            label = if (state.isRefreshing) "Refreshing library…" else "Refresh library",
            hint = state.lastRefreshedAt?.let {
                "Last refreshed " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
            } ?: "No completed library refresh yet",
            modifier = Modifier.focusRequester(firstAction),
            onClick = onRefreshLibrary
        )
        state.refreshMessage?.let { message ->
            Text(message, color = AppColors.Dim, style = TvType.label)
        }

        ActionRow(
            label = "Show subscription",
            hint = "Status, expiry and connections, as this panel reports them",
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
        Spacer(Modifier.height(36.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(AppColors.Line)
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
                if (activeViewModel != null) activeViewModel.signOut()
                else onConfirmSignOutOverride()
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
    value: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusFrame()
            .tvClickable { onClick() }
            .semantics(mergeDescendants = true) {
                contentDescription = "$label${value?.let { ": $it" } ?: ""}. $hint"
            }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = if (destructive) AppColors.Error else AppColors.Ink,
                style = TvType.title,
                modifier = Modifier.weight(1f)
            )
            value?.let { Text(text = it, color = AppColors.AccentText, style = TvType.title) }
        }
        Text(text = hint, color = AppColors.Dim, style = TvType.label)
    }
}
