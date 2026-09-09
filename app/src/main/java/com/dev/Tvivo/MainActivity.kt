package com.dev.Tvivo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.auth.LoginScreen
import com.dev.Tvivo.diagnostics.CodecProbe
import com.dev.Tvivo.sync.RefreshWorker
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.ui.browse.BrowseScreen
import com.dev.Tvivo.ui.common.SplashScreen
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.home.HomeScreen
import com.dev.Tvivo.ui.settings.AccountViewModel
import com.dev.Tvivo.ui.series.SeriesDetailScreen
import com.dev.Tvivo.ui.settings.SettingsScreen
import com.dev.Tvivo.ui.settings.DiagnosticsScreen
import com.dev.Tvivo.ui.settings.SubscriptionScreen
import com.dev.Tvivo.ui.player.PlayerActivity
import com.dev.Tvivo.data.StreamUrlBuilder
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import android.content.Intent
import android.os.Build
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvivoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this `Modifier.imePadding()` is a no-op: Compose reads `WindowInsets.ime`,
        // and while the decor still fits system windows the framework consumes the inset
        // itself and reports zero to Compose. `adjustResize` in the manifest is necessary
        // but not sufficient. The symptom was the login screen's Sign in / Clear row
        // sitting under the TV keyboard, which covers roughly the lower half of a 1080p
        // panel, and only appearing once focus reached the row and dismissed the IME.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Q-22 — the first line on the Diagnostics screen. Without a start marker there
        // is no way to tell, after the fact, whether an empty log means nothing went
        // wrong or that the process was restarted underneath the problem.
        DiagnosticLog.info(
            "app",
            "Started, version ${BuildConfig.VERSION_NAME} on API ${Build.VERSION.SDK_INT}"
        )
        if (BuildConfig.DEBUG) CodecProbe.log()
        // Phase 5. Idempotent and cheap; scheduling here rather than in an Application
        // subclass keeps it in the one place that already owns start-up ordering.
        RefreshWorker.schedule(applicationContext)
        setContent {
            TvivoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = SurfaceDefaults.colors(containerColor = Palette.Bg)
                ) {
                    TvivoApp()
                }
            }
        }
    }
}

private sealed interface Route {
    /** D-10. Carries its own progress so the splash can report real steps rather than
     *  animate a bar on a timer. */
    data class Loading(val progress: Float, val caption: String) : Route
    data object Login : Route
    data class Home(val credentials: Credentials) : Route
    data class Browse(val credentials: Credentials, val type: ContentType) : Route

    /** Series has a layer the other two do not: a show is not playable, so activating
     *  one opens its season/episode picker rather than the player. */
    data class SeriesDetail(val credentials: Credentials, val seriesId: Int) : Route
    data class Settings(val credentials: Credentials) : Route

    /** D-15. Read-only, and its own screen: Account keeps the actions, this keeps the
     *  facts. Back returns to Account, not to Home. */
    data class Subscription(val credentials: Credentials) : Route

    /** Phase 5. Read-only, and reached from Account. */
    data class Diagnostics(val credentials: Credentials) : Route
}

@Composable
private fun TvivoApp() {
    val context = LocalContext.current
    val store = remember { CredentialsStore(context.applicationContext) }
    var route by remember {
        mutableStateOf<Route>(Route.Loading(0.15f, "Starting…"))
    }

    // Credentials are entered once; every later launch resolves them off the main
    // thread and lands straight on Home.
    //
    // The splash reports the two steps that actually take the time — the Tink decrypt,
    // then opening a Room database holding ~68k cached rows. Reporting them honestly is
    // the point of D-10: a determinate bar that lies is worse than a spinner.
    LaunchedEffect(Unit) {
        route = Route.Loading(0.35f, "Unlocking your account")
        val credentials = store.load()
        route = Route.Loading(0.8f, "Opening your library")
        AppDatabase.get(context.applicationContext)
        route = credentials?.let { Route.Home(it) } ?: Route.Login
    }

    // The tile Home returns focus to. Kept out of Route.Home so Back restores it
    // without the Home route having to carry navigation history.
    var lastOpened by remember { mutableStateOf<ContentType?>(null) }

    // Set when Login was opened from Settings with an account still signed in, so Back
    // returns to Home instead of exiting an app the user is still signed into.
    var switchingAccount by remember { mutableStateOf<Credentials?>(null) }

    val account: AccountViewModel = viewModel()
    val accountState by account.state.collectAsStateWithLifecycle()

    // Browse and Settings both go back to Home; Home and Login are the top of the stack
    // and fall through to the default (exit-to-launcher) behaviour.
    BackHandler(
        enabled = route is Route.Browse ||
            route is Route.SeriesDetail ||
            route is Route.Settings ||
            route is Route.Subscription ||
            route is Route.Diagnostics ||
            (route is Route.Login && switchingAccount != null)
    ) {
        when (val current = route) {
            is Route.Browse -> route = Route.Home(current.credentials)
            // Back out of a show returns to the shows grid, not to Home: the grid is
            // where the user was, and it restores its own focus by item id.
            is Route.SeriesDetail ->
                route = Route.Browse(current.credentials, ContentType.SERIES)
            is Route.Settings -> route = Route.Home(current.credentials)
            // Back out of Subscription returns to Account, which is where it was opened
            // from — skipping to Home would lose the user's place in the action list.
            is Route.Subscription -> route = Route.Settings(current.credentials)
            is Route.Diagnostics -> route = Route.Settings(current.credentials)
            is Route.Login -> switchingAccount?.let {
                switchingAccount = null
                route = Route.Home(it)
            }
            else -> Unit
        }
    }

    when (val current = route) {
        is Route.Loading -> SplashScreen(
            progress = current.progress,
            caption = current.caption
        )

        Route.Login -> LoginScreen(
            onAuthenticated = { authenticated ->
                // Re-read the account: after a sign-out or a switch, the strip on Home
                // would otherwise still describe the previous subscription.
                switchingAccount = null
                account.load()
                route = Route.Home(authenticated.credentials)
            }
        )

        is Route.Home -> HomeScreen(
            lastSelected = lastOpened,
            accountSummary = accountState.summary,
            accountWarning = accountState.expiringSoon,
            // D-7/D-17: Refresh and Exit are global actions and live in Home's icon row.
            isRefreshing = accountState.isRefreshing,
            refreshMessage = accountState.refreshMessage,
            onRefreshEverything = account::refreshEverything,
            onSelect = { type ->
                lastOpened = type
                route = Route.Browse(current.credentials, type)
            },
            onOpenSettings = { route = Route.Settings(current.credentials) },
            onExit = { (context as? android.app.Activity)?.finish() }
        )

        is Route.Settings -> SettingsScreen(
            viewModel = account,
            onSignedOut = { route = Route.Login },
            // Switching keeps the current account signed in until the new sign-in is
            // accepted, so a mistyped server does not strand the user signed out.
            onSwitchAccount = {
                switchingAccount = current.credentials
                route = Route.Login
            },
            onShowSubscription = { route = Route.Subscription(current.credentials) },
            onShowDiagnostics = { route = Route.Diagnostics(current.credentials) }
        )

        is Route.Subscription -> SubscriptionScreen(viewModel = account)

        is Route.Diagnostics -> DiagnosticsScreen()

        is Route.Browse -> {
            val isLive = current.type == ContentType.LIVE
            val isSeries = current.type == ContentType.SERIES
            val accountId = remember(current.credentials) {
                AccountIdentity.of(current.credentials)
            }
            BrowseScreen(
                contentType = current.type,
                // For movies and live this plays; for series the item is a *show*, which
                // addresses no stream endpoint, so it opens the episode picker instead.
                onPlay = { item, resumeFromMs ->
                    if (isSeries) {
                        route = Route.SeriesDetail(current.credentials, item.id)
                        return@BrowseScreen
                    }
                    // Live and movies differ in the URL path segment and in the default
                    // extension — `ts` for live, `mp4` for a film — and in nothing else.
                    val url = if (isLive) {
                        StreamUrlBuilder.live(current.credentials, item.id, item.extension ?: "ts")
                    } else {
                        StreamUrlBuilder.movie(current.credentials, item.id, item.extension ?: "mp4")
                    }
                    context.startActivity(
                        Intent(context, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_URL, url)
                            .putExtra(PlayerActivity.EXTRA_IS_LIVE, isLive)
                            .putExtra(PlayerActivity.EXTRA_ITEM_ID, item.id.toString())
                            .putExtra(
                                PlayerActivity.EXTRA_CONTENT_TYPE,
                                if (isLive) TYPE_LIVE else TYPE_VOD
                            )
                            .putExtra(PlayerActivity.EXTRA_ACCOUNT_ID, accountId)
                            .putExtra(PlayerActivity.EXTRA_RESUME_FROM_MS, resumeFromMs)
                    )
                }
            )
        }

        is Route.SeriesDetail -> {
            val accountId = remember(current.credentials) {
                AccountIdentity.of(current.credentials)
            }
            SeriesDetailScreen(
                seriesId = current.seriesId,
                onPlayEpisode = { episode, resumeFromMs ->
                    // Episodes live on their own path segment and carry their own id —
                    // a string, straight from the panel, never round-tripped through Int.
                    val url = StreamUrlBuilder.episode(
                        current.credentials,
                        episode.episodeId,
                        episode.containerExtension ?: "mkv"
                    )
                    context.startActivity(
                        Intent(context, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_URL, url)
                            .putExtra(PlayerActivity.EXTRA_IS_LIVE, false)
                            .putExtra(PlayerActivity.EXTRA_ITEM_ID, episode.episodeId)
                            .putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, TYPE_SERIES)
                            .putExtra(PlayerActivity.EXTRA_ACCOUNT_ID, accountId)
                            .putExtra(PlayerActivity.EXTRA_RESUME_FROM_MS, resumeFromMs)
                    )
                }
            )
        }
    }
}
