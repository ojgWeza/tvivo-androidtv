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
import androidx.compose.ui.platform.testTag
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
    onShowNetworkDiagnostics: () -> Unit = {},
    focusNetworkDiagnostics: Boolean = false,
    onNetworkDiagnosticsFocusConsumed: () -> Unit = {},
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
    val networkDiagnosticsFocus = remember { FocusRequester() }
    var confirmingSignOut by remember { mutableStateOf(false) }
    val appContext = LocalContext.current
    val preferenceStore = remember(appContext) { PlayerPreferencesStore(appContext) }
    val scope = rememberCoroutineScope()
    val modes by preferenceStore.modes.collectAsStateWithLifecycle(initialValue = com.dev.Tvivo.playback.PlaybackModePreferences.AndroidDefaults)
    val tracks by preferenceStore.tracks.collectAsStateWithLifecycle(initialValue = com.dev.Tvivo.playback.TrackPreferences.AndroidDefaults)
    val themeMode by preferenceStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.Dark)
    var appLanguage by remember(appContext) { mutableStateOf(AppLanguage.current(appContext)) }
    val spacing = adaptiveSpacing()

    LaunchedEffect(Unit) {
        runCatching {
            if (focusNetworkDiagnostics) networkDiagnosticsFocus.requestFocus() else firstAction.requestFocus()
        }
        if (focusNetworkDiagnostics) onNetworkDiagnosticsFocusConsumed()
    }
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
        Text(text = stringResource(com.dev.Tvivo.R.string.appearance), color = AppColors.Ink, style = TvType.title)
        ActionRow(stringResource(com.dev.Tvivo.R.string.theme), stringResource(com.dev.Tvivo.R.string.theme_hint), value = stringResource(if (themeMode == ThemeMode.Dark) com.dev.Tvivo.R.string.theme_dark else com.dev.Tvivo.R.string.theme_light), onClick = {
            scope.launch { preferenceStore.setThemeMode(if (themeMode == ThemeMode.Dark) ThemeMode.Light else ThemeMode.Dark) }
        })
        ActionRow(
            label = stringResource(com.dev.Tvivo.R.string.language_title),
            hint = stringResource(com.dev.Tvivo.R.string.settings_language_hint),
            value = stringResource(when (appLanguage) {
                AppLanguage.System -> com.dev.Tvivo.R.string.language_system_default
                AppLanguage.English -> com.dev.Tvivo.R.string.language_english
                AppLanguage.Arabic -> com.dev.Tvivo.R.string.language_arabic
            }),
            onClick = {
                appLanguage = appLanguage.next()
                AppLanguage.set(appContext, appLanguage)
                (appContext as? android.app.Activity)?.recreate()
            }
        )
        Spacer(Modifier.height(24.dp))
        Text(text = stringResource(com.dev.Tvivo.R.string.settings_player), color = AppColors.Ink, style = TvType.title)
        ActionRow(stringResource(com.dev.Tvivo.R.string.settings_episodes), stringResource(com.dev.Tvivo.R.string.settings_play_next_hint), value = stringResource(when (modes.episodeMode) { PlaybackMode.Off -> com.dev.Tvivo.R.string.playback_mode_off; PlaybackMode.Next -> com.dev.Tvivo.R.string.playback_mode_next; PlaybackMode.Shuffle -> com.dev.Tvivo.R.string.playback_mode_shuffle }), onClick = {
            val next = when (modes.episodeMode) { PlaybackMode.Off -> PlaybackMode.Next; PlaybackMode.Next -> PlaybackMode.Shuffle; PlaybackMode.Shuffle -> PlaybackMode.Off }
            scope.launch { preferenceStore.setEpisodeMode(next) }
        })
        ActionRow(stringResource(com.dev.Tvivo.R.string.settings_movies), stringResource(com.dev.Tvivo.R.string.settings_play_next_hint), value = stringResource(when (modes.movieMode) { PlaybackMode.Off -> com.dev.Tvivo.R.string.playback_mode_off; PlaybackMode.Next -> com.dev.Tvivo.R.string.playback_mode_next; PlaybackMode.Shuffle -> com.dev.Tvivo.R.string.playback_mode_shuffle }), onClick = {
            val next = when (modes.movieMode) { PlaybackMode.Off -> PlaybackMode.Next; PlaybackMode.Next -> PlaybackMode.Shuffle; PlaybackMode.Shuffle -> PlaybackMode.Off }
            scope.launch { preferenceStore.setMovieMode(next) }
        })
        ActionRow(stringResource(com.dev.Tvivo.R.string.settings_preferred_audio), stringResource(com.dev.Tvivo.R.string.settings_audio_hint), value = stringResource(when (tracks.audio) { PreferredAudio.Auto -> com.dev.Tvivo.R.string.playback_audio_auto; PreferredAudio.Arabic -> com.dev.Tvivo.R.string.playback_audio_arabic; PreferredAudio.English -> com.dev.Tvivo.R.string.playback_audio_english }), onClick = {
            val next = when (tracks.audio) { PreferredAudio.Auto -> PreferredAudio.Arabic; PreferredAudio.Arabic -> PreferredAudio.English; PreferredAudio.English -> PreferredAudio.Auto }
            scope.launch { preferenceStore.setAudio(next) }
        })
        ActionRow(stringResource(com.dev.Tvivo.R.string.settings_preferred_subtitles), stringResource(com.dev.Tvivo.R.string.settings_subtitles_hint), value = stringResource(when (tracks.subtitles) { PreferredSubtitles.Off -> com.dev.Tvivo.R.string.playback_subtitles_off; PreferredSubtitles.Arabic -> com.dev.Tvivo.R.string.playback_audio_arabic; PreferredSubtitles.English -> com.dev.Tvivo.R.string.playback_audio_english }), onClick = {
            val next = when (tracks.subtitles) { PreferredSubtitles.Off -> PreferredSubtitles.Arabic; PreferredSubtitles.Arabic -> PreferredSubtitles.English; PreferredSubtitles.English -> PreferredSubtitles.Off }
            scope.launch { preferenceStore.setSubtitles(next) }
        })
        Spacer(Modifier.height(24.dp))
        Text(text = stringResource(com.dev.Tvivo.R.string.settings_account), color = AppColors.Ink, style = TvType.title)
        state.username?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, color = AppColors.Dim, style = TvType.body)
        }
        Spacer(Modifier.height(32.dp))

        ActionRow(
            label = stringResource(if (state.isRefreshing) com.dev.Tvivo.R.string.settings_refreshing_library else com.dev.Tvivo.R.string.settings_refresh_library),
            hint = state.lastRefreshedAt?.let {
                stringResource(com.dev.Tvivo.R.string.settings_last_refreshed,
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)))
            } ?: stringResource(com.dev.Tvivo.R.string.settings_no_refresh),
            modifier = Modifier.focusRequester(firstAction),
            onClick = onRefreshLibrary
        )
        state.refreshMessage?.let { message ->
            Text(message, color = AppColors.Dim, style = TvType.label)
        }
        state.error?.let { error ->
            Text(
                text = com.dev.Tvivo.ui.common.ErrorCopy.of(error).message,
                color = AppColors.AccentText,
                style = TvType.label
            )
        }

        ActionRow(
            label = stringResource(com.dev.Tvivo.R.string.settings_show_subscription),
            hint = stringResource(com.dev.Tvivo.R.string.settings_subscription_hint),
            onClick = onShowSubscription
        )
        ActionRow(
            label = stringResource(com.dev.Tvivo.R.string.settings_switch_account),
            hint = stringResource(com.dev.Tvivo.R.string.settings_switch_account_hint),
            onClick = onSwitchAccount
        )
        ActionRow(
            label = stringResource(com.dev.Tvivo.R.string.settings_diagnostics),
            hint = stringResource(com.dev.Tvivo.R.string.settings_diagnostics_hint),
            onClick = onShowDiagnostics
        )
        ActionRow(
            label = stringResource(com.dev.Tvivo.R.string.network_diagnostics_entry),
            hint = stringResource(com.dev.Tvivo.R.string.network_diagnostics_entry_hint),
            modifier = Modifier.focusRequester(networkDiagnosticsFocus).testTag("networkDiagnosticsEntry"),
            onClick = onShowNetworkDiagnostics
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
            label = stringResource(com.dev.Tvivo.R.string.settings_sign_out),
            // The consequence, not the mechanism. "Forgets the stored credentials" reads
            // like a preference being reset; this says what the user actually loses.
            hint = stringResource(com.dev.Tvivo.R.string.settings_sign_out_hint),
            destructive = true,
            onClick = { confirmingSignOut = true }
        )
    }

    if (confirmingSignOut) {
        ConfirmDialog(
            title = stringResource(com.dev.Tvivo.R.string.settings_sign_out_title,
                state.username ?: stringResource(com.dev.Tvivo.R.string.settings_this_account)),
            // The Tink keyset is not exportable, so this is not recoverable from a backup
            // either — the only way back is re-typing ~44 characters on a D-pad.
            consequence = stringResource(com.dev.Tvivo.R.string.settings_sign_out_consequence),
            confirmLabel = stringResource(com.dev.Tvivo.R.string.settings_sign_out_confirm),
            safeLabel = stringResource(com.dev.Tvivo.R.string.settings_sign_out_keep),
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
