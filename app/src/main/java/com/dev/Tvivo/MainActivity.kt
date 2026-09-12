package com.dev.Tvivo

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.ui.detail.ItemDetailScreen
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
            "App", "started",
            "version=${BuildConfig.VERSION_NAME} build=${BuildConfig.BUILD_TYPE} api=${Build.VERSION.SDK_INT} " +
                "device=${Build.MANUFACTURER} ${Build.MODEL}"
        )
        logWindowMetrics("Started")
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        logWindowMetrics("Configuration changed")
    }

    private fun logWindowMetrics(event: String) {
        val metrics = resources.displayMetrics
        val orientation = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }
        DiagnosticLog.info(
            "Window", event,
            "orientation=$orientation size=${metrics.widthPixels}x${metrics.heightPixels}px density=${metrics.densityDpi}"
        )
    }
}

/**
 * The one place a movie or channel URL is built and handed to the player.
 *
 * There are two ways in — the pre-run page's Play, and the grid's long-press shortcut —
 * and they must not drift: live and movies differ only in the URL path segment and the
 * default extension (`ts` against `mp4`), which is exactly the kind of difference that
 * gets fixed in one copy and not the other.
 */
private fun playItem(
    context: android.content.Context,
    credentials: Credentials,
    accountId: String,
    isLive: Boolean,
    itemId: Int,
    extension: String?,
    resumeFromMs: Long
) {
    val url = if (isLive) {
        StreamUrlBuilder.live(credentials, itemId, extension ?: "ts")
    } else {
        StreamUrlBuilder.movie(credentials, itemId, extension ?: "mp4")
    }
    context.startActivity(
        Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, url)
            .putExtra(PlayerActivity.EXTRA_IS_LIVE, isLive)
            .putExtra(PlayerActivity.EXTRA_ITEM_ID, itemId.toString())
            .putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, if (isLive) TYPE_LIVE else TYPE_VOD)
            .putExtra(PlayerActivity.EXTRA_ACCOUNT_ID, accountId)
            .putExtra(PlayerActivity.EXTRA_RESUME_FROM_MS, resumeFromMs)
    )
}

internal sealed interface Route {
    /** D-10. Carries its own progress so the splash can report real steps rather than
     *  animate a bar on a timer. */
    data class Loading(val progress: Float, val caption: String) : Route
    data object Login : Route
    data class Home(val credentials: Credentials) : Route
    data class Browse(val credentials: Credentials, val type: ContentType) : Route

    /**
     * The pre-run page. Activating any card lands here rather than in the player — see
     * [ItemDetailScreen] for why. Carries the type as well as the id because
     * `stream_id` is only unique *within* a content type on this panel.
     */
    data class ItemDetail(
        val credentials: Credentials,
        val type: ContentType,
        val itemId: Int
    ) : Route

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

/** Kept outside composition so all three Home transitions are unit-testable. */
internal fun browseRoute(credentials: Credentials, type: ContentType): Route.Browse =
    Route.Browse(credentials, type)

private fun Route.diagnosticName(): String = when (this) {
    is Route.Loading -> "Loading"
    Route.Login -> "Login"
    is Route.Home -> "Home"
    is Route.Browse -> "Browse ${type.name}"
    is Route.ItemDetail -> "Detail ${type.name}"
    is Route.SeriesDetail -> "Series detail"
    is Route.Settings -> "Settings"
    is Route.Subscription -> "Subscription"
    is Route.Diagnostics -> "Diagnostics"
}

@Composable
private fun TvivoApp() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTelevision = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION
    val store = remember { CredentialsStore(context.applicationContext) }
    var route by remember {
        mutableStateOf<Route>(Route.Loading(0.15f, "Starting…"))
    }

    LaunchedEffect(route) {
        DiagnosticLog.info("Navigation", "destination composed", "route=${route.diagnosticName()}")
    }

    // Handset login is a touch-first portrait form; the catalog is designed around wide
    // cards and rail navigation, so it returns to landscape once an account is active.
    // MainActivity handles these configuration changes itself so changing orientation does
    // not recreate the activity and discard an in-progress sign-in or account switch.
    LaunchedEffect(isTelevision, route) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        val requestedOrientation = when {
            isTelevision -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            route is Route.Login || route is Route.Loading ->
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (activity.requestedOrientation != requestedOrientation) {
            activity.requestedOrientation = requestedOrientation
            val orientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                "portrait"
            } else if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                "landscape"
            } else {
                "system default"
            }
            DiagnosticLog.info(
                "Window", "orientation requested",
                "orientation=$orientation route=${route.diagnosticName()} television=$isTelevision"
            )
        }
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
            route is Route.ItemDetail ||
            route is Route.SeriesDetail ||
            route is Route.Settings ||
            route is Route.Subscription ||
            route is Route.Diagnostics ||
            (route is Route.Login && switchingAccount != null)
    ) {
        when (val current = route) {
            is Route.Browse -> route = Route.Home(current.credentials)
            // Back out of the pre-run page returns to the grid it was opened from, which
            // restores focus to the card by item id.
            is Route.ItemDetail -> route = Route.Browse(current.credentials, current.type)
            // Back out of the episode picker returns to the show's pre-run page, which
            // is the screen it was opened from. Going straight to the grid would skip a
            // step the user walked through and lose the heart they may have come back
            // for.
            is Route.SeriesDetail ->
                route = Route.ItemDetail(
                    current.credentials,
                    ContentType.SERIES,
                    current.seriesId
                )
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
                runCatching {
                    DiagnosticLog.info("Home", "route requested", "contentType=${type.name} route=Browse")
                    lastOpened = type
                    route = browseRoute(current.credentials, type)
                    DiagnosticLog.info(
                        "Navigation", "route state mutated",
                        "route=Browse contentType=${type.name}"
                    )
                }.onFailure { error ->
                    DiagnosticLog.error(
                        "Home", "route transition rejected",
                        "contentType=${type.name} error=${error.javaClass.simpleName}"
                    )
                }
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
                // Activating a card no longer plays anything: every type opens the
                // pre-run page, which owns Play, the description and the heart.
                onOpenDetail = { item ->
                    route = Route.ItemDetail(current.credentials, current.type, item.id)
                },
                // The long-press menu keeps its direct Play. It is the deliberate
                // shortcut for someone who already knows what the item is, and taking it
                // away would make the new page a tax rather than a step.
                onPlay = { item, resumeFromMs ->
                    if (isSeries) {
                        route = Route.SeriesDetail(current.credentials, item.id)
                        return@BrowseScreen
                    }
                    playItem(
                        context = context,
                        credentials = current.credentials,
                        accountId = accountId,
                        isLive = isLive,
                        itemId = item.id,
                        extension = item.extension,
                        resumeFromMs = resumeFromMs
                    )
                }
            )
        }

        is Route.ItemDetail -> {
            val accountId = remember(current.credentials) {
                AccountIdentity.of(current.credentials)
            }
            val detailIsLive = current.type == ContentType.LIVE
            ItemDetailScreen(
                contentType = current.type,
                itemId = current.itemId,
                onPlay = { resumeFromMs, extension ->
                    playItem(
                        context = context,
                        credentials = current.credentials,
                        accountId = accountId,
                        isLive = detailIsLive,
                        itemId = current.itemId,
                        extension = extension,
                        resumeFromMs = resumeFromMs
                    )
                },
                // A show's Play is "show me the episodes" — `series_id` addresses no
                // stream endpoint, so the picker is the only thing it can mean.
                onOpenEpisodes = {
                    route = Route.SeriesDetail(current.credentials, current.itemId)
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
