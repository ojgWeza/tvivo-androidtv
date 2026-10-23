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
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.auth.LoginScreen
import com.dev.Tvivo.auth.SourceSession
import com.dev.Tvivo.auth.SourceKind
import com.dev.Tvivo.auth.SourceSnapshot
import com.dev.Tvivo.auth.SourceStore
import com.dev.Tvivo.auth.AndroidDriveGateway
import com.dev.Tvivo.auth.DriveFlowViewModel
import com.dev.Tvivo.auth.DriveFlowState
import com.dev.Tvivo.auth.DriveFlowScreen
import com.dev.Tvivo.auth.DriveHomeScreen
import com.dev.Tvivo.auth.DriveAccountScreen
import com.dev.Tvivo.auth.DriveAccount
import com.dev.Tvivo.auth.DriveFailureCopy
import com.dev.Tvivo.diagnostics.CodecProbe
import com.dev.Tvivo.sync.RefreshWorker
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.ui.browse.BrowseScreen
import com.dev.Tvivo.ui.browse.CatalogLandingScreen
import com.dev.Tvivo.ui.common.SplashScreen
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.home.HomeScreen
import com.dev.Tvivo.home.ShellNavigation
import com.dev.Tvivo.home.ShellTab
import com.dev.Tvivo.home.ShelfRestoreKey
import com.dev.Tvivo.ui.shell.AppShell
import com.dev.Tvivo.ui.shell.ScreenTopBar
import com.dev.Tvivo.ui.shell.SearchScreen
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.browse.VirtualFolder
import com.dev.Tvivo.ui.settings.AccountViewModel
import com.dev.Tvivo.ui.series.SeriesDetailScreen
import com.dev.Tvivo.ui.settings.SettingsScreen
import com.dev.Tvivo.ui.settings.DiagnosticsScreen
import com.dev.Tvivo.ui.settings.NetworkDiagnosticsScreen
import com.dev.Tvivo.ui.settings.SubscriptionScreen
import com.dev.Tvivo.ui.player.PlayerActivity
import com.dev.Tvivo.data.StreamUrlBuilder
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.local.entities.RecentlyPlayedEntity
import com.dev.Tvivo.data.local.entities.ShelfVisitEntity
import com.dev.Tvivo.data.repository.roomKey
import com.dev.Tvivo.diagnostics.DiagnosticLog
import com.dev.Tvivo.diagnostics.NetworkDiagnosticsRuntime
import com.dev.Tvivo.diagnostics.DeepStreamCheckGate
import com.dev.Tvivo.ui.detail.ItemDetailScreen
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvivoTheme
import com.dev.Tvivo.ui.ThemeMode
import com.dev.Tvivo.playback.PlayerPreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.dev.Tvivo.ui.settings.AppLanguage.wrap(newBase))
    }

    private lateinit var initialThemeMode: ThemeMode
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearance = PlayerPreferencesStore(applicationContext)
        initialThemeMode = runBlocking { appearance.themeMode.first() }
        appearance.syncSystemTheme(initialThemeMode)
        val launchColor = (if (initialThemeMode == ThemeMode.Light) com.dev.Tvivo.ui.theme.LightAppColors else com.dev.Tvivo.ui.theme.DefaultAppColors).bg.toArgb()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(launchColor))
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
        NetworkDiagnosticsRuntime.start(applicationContext)
        logWindowMetrics("Started")
        if (BuildConfig.DEBUG) CodecProbe.log()
        // Phase 5. Idempotent and cheap; scheduling here rather than in an Application
        // subclass keeps it in the one place that already owns start-up ordering.
        lifecycleScope.launch(Dispatchers.IO) { RefreshWorker.schedule(applicationContext) }
        setContent {
            val themeMode by PlayerPreferencesStore(LocalContext.current).themeMode.collectAsState(initial = initialThemeMode)
            SideEffect {
                val systemBarColor = (if (themeMode == ThemeMode.Light) com.dev.Tvivo.ui.theme.LightAppColors else com.dev.Tvivo.ui.theme.DefaultAppColors).bg.toArgb()
                window.statusBarColor = systemBarColor
                window.navigationBarColor = systemBarColor
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = themeMode == ThemeMode.Light
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = themeMode == ThemeMode.Light
            }
            TvivoTheme(mode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                    colors = SurfaceDefaults.colors(containerColor = AppColors.Bg)
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

internal fun shouldAutoShowLoginKeyboard(isTelevision: Boolean, switchingAccount: Boolean): Boolean =
    isTelevision && !switchingAccount

internal fun sourceStartupRoute(
    snapshot: SourceSnapshot,
    legacyCredentials: Credentials?,
    driveSourceEnabled: Boolean,
    tab: ShellTab
): Route {
    if (!driveSourceEnabled) return legacyCredentials?.let { Route.Main(it, tab) } ?: Route.Login
    return when (val active = snapshot.active) {
        null -> Route.Login
        else -> when (active.kind) {
            SourceKind.XTREAM -> active.credentials?.let { Route.Main(it, tab) } ?: Route.Login
            SourceKind.DRIVE -> active.permissionId?.let {
                Route.DriveHome(SourceSession.Drive(active.id, it))
            } ?: Route.Login
        }
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
    title: String,
    extension: String?,
    resumeFromMs: Long,
    knownDurationMs: Long = 0L
) {
    if (DeepStreamCheckGate.isHeld) {
        android.widget.Toast.makeText(context, R.string.network_check_in_progress, android.widget.Toast.LENGTH_SHORT).show()
        return
    }
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
            .putExtra(PlayerActivity.EXTRA_TITLE, title)
            .putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, if (isLive) TYPE_LIVE else TYPE_VOD)
            .putExtra(PlayerActivity.EXTRA_ACCOUNT_ID, accountId)
            .putExtra(PlayerActivity.EXTRA_RESUME_FROM_MS, resumeFromMs)
            .putExtra(PlayerActivity.EXTRA_KNOWN_DURATION_MS, knownDurationMs.coerceAtLeast(0L))
            .putExtra(PlayerActivity.EXTRA_REQUEST_ID, java.util.UUID.randomUUID().toString())
    )
    (context as? ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
        AppDatabase.get(context).recentlyPlayedDao().record(
            RecentlyPlayedEntity(
                profileId = accountId,
                accountKey = accountId,
                contentType = if (isLive) TYPE_LIVE else TYPE_VOD,
                itemId = itemId.toString(),
                playedAt = System.currentTimeMillis()
            )
        )
    }
}

internal sealed interface Route {
    /** D-10. Carries its own progress so the splash can report real steps rather than
     *  animate a bar on a timer. */
    data class Loading(val progress: Float, val caption: String) : Route
    data object Login : Route
    data object DriveFlow : Route
    data class DriveHome(val session: SourceSession.Drive) : Route
    data class DriveAccount(val session: SourceSession.Drive) : Route
    data class Main(val credentials: Credentials, val tab: ShellTab) : Route
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

    data class NetworkDiagnostics(val credentials: Credentials) : Route
}

/** Kept outside composition so all three Home transitions are unit-testable. */
internal fun browseRoute(credentials: Credentials, type: ContentType): Route.Browse =
    Route.Browse(credentials, type)

internal fun shouldReturnToSearchAfterSeeAll(wasOpenedFromSearch: Boolean, currentTabIsSearch: Boolean) =
    wasOpenedFromSearch && !currentTabIsSearch

internal data class ShellBrowseState(val categoryId: String? = null, val query: String = "") {
    fun savedFields(): List<String> = listOf(categoryId.orEmpty(), query)
    companion object {
        fun restore(fields: List<String>): ShellBrowseState =
            ShellBrowseState(fields.firstOrNull()?.takeIf { it.isNotEmpty() }, fields.getOrNull(1).orEmpty())
    }
}

private val shellBrowseSaver = listSaver<ShellBrowseState, String>(
    save = { it.savedFields() },
    restore = { ShellBrowseState.restore(it) },
)

private fun Route.diagnosticName(): String = when (this) {
    is Route.Loading -> "Loading"
    Route.Login -> "Login"
    Route.DriveFlow -> "Drive authorization"
    is Route.DriveHome -> "Drive home"
    is Route.DriveAccount -> "Drive account"
    is Route.Main -> "Main ${tab.name}"
    is Route.Home -> "Home"
    is Route.Browse -> "Browse ${type.name}"
    is Route.ItemDetail -> "Detail ${type.name}"
    is Route.SeriesDetail -> "Series detail"
    is Route.Settings -> "Settings"
    is Route.Subscription -> "Subscription"
    is Route.Diagnostics -> "Diagnostics"
    is Route.NetworkDiagnostics -> "Network diagnostics"
}

@Composable
private fun TvivoApp() {
    val context = LocalContext.current
    val isTelevision = LocalIsTelevision.current
    val store = remember { CredentialsStore(context.applicationContext) }
    val driveGateway = remember(context) { AndroidDriveGateway(context.applicationContext) }
    val driveFlow: DriveFlowViewModel = viewModel(factory = remember(driveGateway) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DriveFlowViewModel(driveGateway) as T
        }
    })
    val driveFlowState by driveFlow.state.collectAsStateWithLifecycle()
    var driveAccount by remember { mutableStateOf<DriveAccount?>(null) }
    var driveSeeded by remember { mutableStateOf(false) }
    var driveSyncing by remember { mutableStateOf(false) }
    var driveSyncMessage by remember { mutableStateOf<String?>(null) }
    var driveSyncJob by remember { mutableStateOf<Job?>(null) }
    var route by remember {
        mutableStateOf<Route>(Route.Loading(0.15f, "Starting…"))
    }
    var savedTab by rememberSaveable { mutableStateOf(ShellTab.MyTvivo) }
    var searchSeeAllReturn by rememberSaveable { mutableStateOf(false) }
    var detailOriginTab by rememberSaveable { mutableStateOf(ShellTab.MyTvivo) }
    var topLevelHistory by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var shellBrowse by rememberSaveable(stateSaver = shellBrowseSaver) {
        mutableStateOf(ShellBrowseState())
    }
    var pendingHomeFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSearchFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCatalogFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    var focusSettingsNetworkDiagnostics by rememberSaveable { mutableStateOf(false) }
    val tabStates = rememberSaveableStateHolder()
    var shellSortChoice by rememberSaveable { mutableStateOf(com.dev.Tvivo.home.CatalogSort.MostVisited) }
    var shellSortRevision by remember { mutableIntStateOf(0) }
    var shellRefreshRevision by remember { mutableIntStateOf(0) }
    val shellScope = rememberCoroutineScope()
    val playerPreferences = remember(context) { PlayerPreferencesStore(context.applicationContext) }
    val themeMode by playerPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.Dark)

    LaunchedEffect(route) {
        DiagnosticLog.info("Navigation", "destination composed", "route=${route.diagnosticName()}")
    }

    // Handset login is portrait; signed-in routes follow the window size class.
    // MainActivity handles these configuration changes itself so changing orientation does
    // not recreate the activity and discard an in-progress sign-in or account switch.
    LaunchedEffect(isTelevision, route) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        val requestedOrientation = when {
            isTelevision -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            route is Route.Login || route is Route.Loading || route is Route.DriveFlow ->
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        val snapshot = if (BuildConfig.DRIVE_SOURCE_ENABLED) SourceStore.open(context.applicationContext).load()
            else SourceSnapshot(emptyList(), null)
        route = Route.Loading(0.8f, "Opening your library")
        AppDatabase.get(context.applicationContext)
        val destination = sourceStartupRoute(snapshot, credentials, BuildConfig.DRIVE_SOURCE_ENABLED, savedTab)
        if (destination is Route.DriveHome) driveAccount = driveGateway.savedAccount(destination.session)
        route = destination
    }

    // The tile Home returns focus to. Kept out of Route.Home so Back restores it
    // without the Home route having to carry navigation history.
    var lastOpened by rememberSaveable { mutableStateOf<ContentType?>(null) }

    // Set when Login was opened from Settings with an account still signed in, so Back
    // returns to Home instead of exiting an app the user is still signed into.
    var switchingAccount by remember { mutableStateOf<Credentials?>(null) }
    var switchingDrive by remember { mutableStateOf<SourceSession.Drive?>(null) }

    val account: AccountViewModel = viewModel()
    val accountState by account.state.collectAsStateWithLifecycle()
    var homeExitRequest by remember { mutableIntStateOf(0) }

    // Browse and Settings both go back to Home; Home and Login are the top of the stack
    // and fall through to the default (exit-to-launcher) behaviour.
    BackHandler(
        enabled = route is Route.Main ||
            route is Route.Browse ||
            route is Route.ItemDetail ||
            route is Route.SeriesDetail ||
            route is Route.Settings ||
            route is Route.Subscription ||
            route is Route.Diagnostics ||
            route is Route.NetworkDiagnostics ||
            route is Route.DriveFlow ||
            route is Route.DriveAccount ||
            route is Route.DriveHome ||
            (route is Route.Login && (switchingAccount != null || switchingDrive != null))
    ) {
        when (val current = route) {
            is Route.Main -> {
                if (shouldReturnToSearchAfterSeeAll(searchSeeAllReturn, current.tab == ShellTab.Search)) {
                    searchSeeAllReturn = false
                    savedTab = ShellTab.Search
                    route = Route.Main(current.credentials, ShellTab.Search)
                    return@BackHandler
                }
                if (current.tab == ShellTab.MyTvivo) {
                    homeExitRequest++
                    return@BackHandler
                }
                when (ShellNavigation.backFromTopLevel(current.tab)) {
                    ShellNavigation.BackAction.ShowExitConfirmation -> homeExitRequest++
                    ShellNavigation.BackAction.ReturnToMyTvivo -> {
                        if (isTelevision) topLevelHistory = arrayListOf()
                        savedTab = ShellTab.MyTvivo
                        route = Route.Main(current.credentials, ShellTab.MyTvivo)
                    }
                }
            }
            is Route.Browse -> route = Route.Main(current.credentials, ShellTab.MyTvivo)
            // Back out of the pre-run page returns to the grid it was opened from, which
            // restores focus to the card by item id.
            is Route.ItemDetail -> route = Route.Main(current.credentials, detailOriginTab)
            is Route.SeriesDetail -> route = Route.Main(current.credentials, detailOriginTab)
            is Route.Settings -> route = Route.Main(current.credentials, ShellTab.MyTvivo)
            // Back out of Subscription returns to Account, which is where it was opened
            // from — skipping to Home would lose the user's place in the action list.
            is Route.Subscription -> route = Route.Main(current.credentials, ShellTab.Settings)
            is Route.Diagnostics -> route = Route.Main(current.credentials, ShellTab.Settings)
            is Route.NetworkDiagnostics -> route = Route.Main(current.credentials, ShellTab.Settings)
            Route.DriveFlow -> { driveFlow.cancel(); route = Route.Login }
            is Route.DriveAccount -> route = Route.DriveHome(current.session)
            is Route.DriveHome -> (context as? android.app.Activity)?.finish()
            is Route.Login -> switchingAccount?.let {
                switchingAccount = null
                savedTab = ShellTab.MyTvivo
                route = Route.Main(it, ShellTab.MyTvivo)
            } ?: switchingDrive?.let {
                switchingDrive = null
                route = Route.DriveHome(it)
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
            showKeyboardOnOpen = shouldAutoShowLoginKeyboard(
                isTelevision = LocalIsTelevision.current, switchingAccount = switchingAccount != null || switchingDrive != null),
            driveSourceEnabled = BuildConfig.DRIVE_SOURCE_ENABLED,
            onDriveSignIn = { if (BuildConfig.DRIVE_SOURCE_ENABLED) route = Route.DriveFlow },
            onAuthenticated = { authenticated ->
                // Re-read the account: after a sign-out or a switch, the strip on Home
                // would otherwise still describe the previous subscription.
                switchingAccount = null
                switchingDrive = null
                account.load()
                savedTab = ShellTab.MyTvivo
                pendingHomeFocusKey = null
                pendingSearchFocusKey = null
                shellBrowse = ShellBrowseState()
                topLevelHistory = arrayListOf()
                lastOpened = null
                route = Route.Main(authenticated.credentials, ShellTab.MyTvivo)
            }
        )

        Route.DriveFlow -> {
            LaunchedEffect(Unit) { driveFlow.start(isTelevision) }
            LaunchedEffect(driveFlowState) {
                val ready = driveFlowState as? DriveFlowState.Ready ?: return@LaunchedEffect
                driveAccount = ready.account
                driveSeeded = ready.seeded
                switchingAccount = null
                switchingDrive = null
                driveSyncMessage = null
                route = Route.DriveHome(ready.account.session)
            }
            DriveFlowScreen(driveFlowState,
                onRefreshCode = driveFlow::refreshCode,
                onRetry = {
                    if (driveFlowState is DriveFlowState.Failed &&
                        (driveFlowState as DriveFlowState.Failed).retryAuthorization)
                        driveFlow.start(isTelevision)
                    else driveFlow.retry()
                },
                onChooseFolder = driveFlow::chooseFolder,
                onCancel = { driveFlow.cancel(); route = Route.Login })
        }

        is Route.DriveHome -> {
            val accountInfo = driveAccount
            if (accountInfo == null || accountInfo.session.id != current.session.id) {
                LaunchedEffect(current.session) { driveAccount = driveGateway.savedAccount(current.session) }
            } else {
                if (accountInfo.displayName == null && accountInfo.email == null) LaunchedEffect(current.session) {
                    val refreshed = driveGateway.refreshIdentity(accountInfo)
                    if (route == current && refreshed != accountInfo) driveAccount = refreshed
                }
                DriveHomeScreen(accountInfo, driveSeeded) { route = Route.DriveAccount(current.session) }
            }
        }

        is Route.DriveAccount -> {
            val accountInfo = driveAccount
            if (accountInfo == null || accountInfo.session.id != current.session.id) {
                LaunchedEffect(current.session) { driveAccount = driveGateway.savedAccount(current.session) }
            } else {
                if (accountInfo.displayName == null && accountInfo.email == null) LaunchedEffect(current.session) {
                    val refreshed = driveGateway.refreshIdentity(accountInfo)
                    if (route == current && refreshed != accountInfo) driveAccount = refreshed
                }
                DriveAccountScreen(accountInfo, driveSyncing, driveSyncMessage,
                onResync = {
                    if (!driveSyncing) {
                        driveSyncing = true
                        driveSyncMessage = null
                        driveSyncJob = shellScope.launch {
                            try {
                                val at = driveGateway.resync(current.session)
                                if (route == current) {
                                    driveAccount = accountInfo.copy(lastSyncAt = at)
                                    driveSyncMessage = "Library is up to date."
                                }
                            } catch (failure: Throwable) {
                                if (failure !is kotlinx.coroutines.CancellationException && route == current)
                                    driveSyncMessage = DriveFailureCopy.message(failure)
                            } finally { driveSyncing = false }
                        }
                    }
                },
                onSwitchSource = {
                    driveSyncJob?.cancel()
                    switchingDrive = current.session
                    route = Route.Login
                },
                onDisconnect = {
                    driveSyncJob?.cancel()
                    shellScope.launch {
                        val revoked = driveGateway.disconnect(current.session)
                        driveAccount = null
                        driveSeeded = false
                        switchingDrive = null
                        route = Route.Login
                        if (!revoked) android.widget.Toast.makeText(context,
                            "Disconnected here. Check Google access separately.", android.widget.Toast.LENGTH_LONG).show()
                    }
                })
            }
        }

        is Route.Home -> LaunchedEffect(current.credentials) {
            route = Route.Main(current.credentials, ShellTab.MyTvivo)
        }

        is Route.Main -> {
            val accountId = remember(current.credentials) { AccountIdentity.of(current.credentials) }
            fun select(tab: ShellTab) {
                searchSeeAllReturn = false
                if (tab != current.tab)
                    topLevelHistory = ArrayList(topLevelHistory).apply { add(current.tab.name) }
                savedTab = tab
                shellBrowse = shellBrowse.copy(categoryId = null)
                route = Route.Main(current.credentials, tab)
            }
            fun openCard(card: com.dev.Tvivo.data.repository.HomeCard, shelfId: String? = null) {
                detailOriginTab = current.tab
                if (current.tab == ShellTab.MyTvivo && shelfId != null)
                    pendingHomeFocusKey = ShelfRestoreKey.of(shelfId, card.key)
                if (current.tab == ShellTab.Search) pendingSearchFocusKey = card.key
                if (isTelevision && card.type == ContentType.LIVE) {
                    playItem(context, current.credentials, accountId, true, card.item.id,
                        card.item.title, card.item.extension, 0L, card.item.knownDurationMs)
                    return
                }
                route = if (card.type == ContentType.SERIES) {
                    Route.SeriesDetail(current.credentials, card.item.id)
                } else Route.ItemDetail(current.credentials, card.type, card.item.id)
            }
            AppShell(
                selected = current.tab,
                onSelect = ::select,
                sortEnabled = current.tab == ShellTab.Movies || current.tab == ShellTab.Series || current.tab == ShellTab.Live,
                openedCategory = shellBrowse.categoryId != null,
                refreshEnabled = current.tab != ShellTab.Search && current.tab != ShellTab.Settings,
                sortChoice = shellSortChoice,
                onSortSelected = { shellSortChoice = it; shellSortRevision++ },
                onRefresh = {
                    if (current.tab == ShellTab.MyTvivo) account.refreshEverything()
                    else shellRefreshRevision++
                },
                onTheme = {
                    shellScope.launch {
                        playerPreferences.setThemeMode(
                            if (themeMode == ThemeMode.Dark) ThemeMode.Light else ThemeMode.Dark)
                    }
                }
            ) {
                tabStates.SaveableStateProvider("$accountId:${current.tab.name}") {
                    when (current.tab) {
                        ShellTab.MyTvivo -> HomeScreen(
                            accountId = accountId,
                            providerHost = current.credentials.host,
                            subscriptionExpiry = accountState.expiry,
                            accountWarning = accountState.expiringSoon,
                            pendingFocusCardKey = pendingHomeFocusKey,
                            lastSelected = lastOpened,
                            isRefreshing = accountState.isRefreshing,
                            refreshMessage = accountState.refreshMessage,
                            onRefreshEverything = account::refreshEverything,
                            onSelect = { type ->
                                lastOpened = type
                                select(when (type) {
                                    ContentType.MOVIES -> ShellTab.Movies
                                    ContentType.SERIES -> ShellTab.Series
                                    ContentType.LIVE -> ShellTab.Live
                                })
                            },
                            onOpenSearch = { select(ShellTab.Search) },
                            onOpenCard = { card, shelfId -> openCard(card, shelfId) },
                            onPlayCard = { card, resumeMs, shelfId ->
                                pendingHomeFocusKey = ShelfRestoreKey.of(shelfId, card.key)
                                if (card.type == ContentType.SERIES) {
                                    detailOriginTab = current.tab
                                    route = Route.SeriesDetail(current.credentials, card.item.id)
                                } else playItem(context, current.credentials, accountId,
                                    card.type == ContentType.LIVE, card.item.id, card.item.title,
                                    card.item.extension, resumeMs, card.item.knownDurationMs)
                            },
                            onSeeAll = { shelf ->
                                if (shelf.id == "__my_suggestions") {
                                    select(ShellTab.Search)
                                } else {
                                    (context as? ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                                        AppDatabase.get(context).shelfVisitDao().record(
                                            ShelfVisitEntity(
                                                profileId = accountId,
                                                accountKey = accountId,
                                                contentType = shelf.type.roomKey(),
                                                categoryId = shelf.categoryId ?: shelf.id,
                                                lastVisitedAt = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                    shellBrowse = shellBrowse.copy(categoryId = shelf.categoryId ?: when {
                                        shelf.id.startsWith("__my_continue_") -> VirtualFolder.CONTINUE_WATCHING.id
                                        shelf.id.startsWith("__my_added_") -> VirtualFolder.RECENTLY_ADDED.id
                                        shelf.id.startsWith("__my_favorites_") -> VirtualFolder.FAVOURITES.id
                                        else -> null
                                    })
                                    topLevelHistory = ArrayList(topLevelHistory).apply { add(current.tab.name) }
                                    savedTab = when (shelf.type) {
                                        ContentType.MOVIES -> ShellTab.Movies
                                        ContentType.SERIES -> ShellTab.Series
                                        ContentType.LIVE -> ShellTab.Live
                                    }
                                    route = Route.Main(current.credentials, savedTab)
                                }
                            },
                            onExit = { (context as? android.app.Activity)?.finish() },
                            exitRequest = homeExitRequest,
                            onExitRequestHandled = { homeExitRequest = 0 },
                            onPendingFocusConsumed = { pendingHomeFocusKey = null }
                        )
                        ShellTab.Search -> SearchScreen(accountId,
                            query = shellBrowse.query,
                            onQueryChanged = { shellBrowse = shellBrowse.copy(query = it) },
                            pendingFocusCardKey = pendingSearchFocusKey,
                            onPendingFocusConsumed = { pendingSearchFocusKey = null },
                            onOpen = { card -> openCard(card) },
                            onBrowse = { type ->
                                select(when (type) {
                                    ContentType.MOVIES -> ShellTab.Movies
                                    ContentType.SERIES -> ShellTab.Series
                                    ContentType.LIVE -> ShellTab.Live
                                })
                                searchSeeAllReturn = true
                            })
                        ShellTab.Settings -> SettingsScreen(
                            viewModel = account,
                            onRefreshLibrary = account::refreshEverything,
                            onSignedOut = {
                                savedTab = ShellTab.MyTvivo
                                pendingHomeFocusKey = null
                                pendingSearchFocusKey = null
                                shellBrowse = ShellBrowseState()
                                topLevelHistory = arrayListOf()
                                route = Route.Login
                            },
                            onSwitchAccount = {
                                switchingAccount = current.credentials
                                route = Route.Login
                            },
                            onShowSubscription = { route = Route.Subscription(current.credentials) },
                            onShowDiagnostics = { route = Route.Diagnostics(current.credentials) },
                            focusNetworkDiagnostics = focusSettingsNetworkDiagnostics,
                            onNetworkDiagnosticsFocusConsumed = { focusSettingsNetworkDiagnostics = false },
                            onShowNetworkDiagnostics = {
                                focusSettingsNetworkDiagnostics = true
                                route = Route.NetworkDiagnostics(current.credentials)
                            }
                        )
                        else -> {
                            val type = when (current.tab) {
                                ShellTab.Movies -> ContentType.MOVIES
                                ShellTab.Series -> ContentType.SERIES
                                else -> ContentType.LIVE
                            }
                            CatalogLandingScreen(
                                contentType = type,
                                accountId = accountId,
                                initialCategoryId = shellBrowse.categoryId,
                                onOpenedCategoryChanged = { shellBrowse = shellBrowse.copy(categoryId = it) },
                                shellSortChoice = shellSortChoice,
                                shellSortRevision = shellSortRevision,
                                shellRefreshRevision = shellRefreshRevision,
                                onShellSortStateChanged = { shellSortChoice = it },
                                pendingFocusCardKey = pendingCatalogFocusKey,
                                onPendingFocusConsumed = { pendingCatalogFocusKey = null },
                                onReturnToLanding = {
                                    if (isTelevision) {
                                        val destination = topLevelHistory.lastOrNull()
                                            ?.let { name -> ShellTab.entries.firstOrNull { it.name == name } }
                                            ?: ShellTab.MyTvivo
                                        topLevelHistory = ArrayList(topLevelHistory.dropLast(1))
                                        savedTab = destination
                                        route = Route.Main(current.credentials, destination)
                                    } else shellBrowse = shellBrowse.copy(categoryId = null)
                                },
                                onOpenDetail = { item, shelfId ->
                                    pendingCatalogFocusKey = ShelfRestoreKey.of(
                                        shelfId, "${type.name}:${item.id}")
                                    detailOriginTab = current.tab
                                    if (isTelevision && type == ContentType.LIVE) {
                                        playItem(context, current.credentials, accountId, true,
                                            item.id, item.title, item.extension, 0L, item.knownDurationMs)
                                    } else route = if (type == ContentType.SERIES) {
                                        Route.SeriesDetail(current.credentials, item.id)
                                    } else Route.ItemDetail(current.credentials, type, item.id)
                                },
                                onOpenDetailFromGrid = { item ->
                                    pendingCatalogFocusKey = null
                                    detailOriginTab = current.tab
                                    if (isTelevision && type == ContentType.LIVE) {
                                        playItem(context, current.credentials, accountId, true,
                                            item.id, item.title, item.extension, 0L, item.knownDurationMs)
                                    } else route = if (type == ContentType.SERIES) {
                                        Route.SeriesDetail(current.credentials, item.id)
                                    } else Route.ItemDetail(current.credentials, type, item.id)
                                },
                                onPlay = { item, resumeFromMs, shelfId ->
                                    pendingCatalogFocusKey = shelfId.takeIf { it.isNotEmpty() }
                                        ?.let { ShelfRestoreKey.of(it, "${type.name}:${item.id}") }
                                    if (type == ContentType.SERIES) {
                                        detailOriginTab = current.tab
                                        route = Route.SeriesDetail(current.credentials, item.id)
                                    } else playItem(context, current.credentials, accountId,
                                        type == ContentType.LIVE, item.id, item.title,
                                        item.extension, resumeFromMs, item.knownDurationMs)
                                },
                                onPlayFromGrid = { item, resumeFromMs ->
                                    pendingCatalogFocusKey = null
                                    if (type == ContentType.SERIES) {
                                        detailOriginTab = current.tab
                                        route = Route.SeriesDetail(current.credentials, item.id)
                                    } else playItem(context, current.credentials, accountId,
                                        type == ContentType.LIVE, item.id, item.title,
                                        item.extension, resumeFromMs, item.knownDurationMs)
                                }
                            )
                        }
                    }
                }
            }
        }

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
            onShowDiagnostics = { route = Route.Diagnostics(current.credentials) },
            focusNetworkDiagnostics = focusSettingsNetworkDiagnostics,
            onNetworkDiagnosticsFocusConsumed = { focusSettingsNetworkDiagnostics = false },
            onShowNetworkDiagnostics = {
                focusSettingsNetworkDiagnostics = true
                route = Route.NetworkDiagnostics(current.credentials)
            }
        )

        is Route.Subscription -> {
            if (LocalIsTelevision.current) SubscriptionScreen(viewModel = account)
            else Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                ScreenTopBar(stringResource(R.string.subscription_title)) { route = Route.Main(current.credentials, ShellTab.Settings) }
                Box(Modifier.weight(1f)) { SubscriptionScreen(viewModel = account) }
            }
        }

        is Route.Diagnostics -> {
            if (LocalIsTelevision.current) DiagnosticsScreen()
            else Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                ScreenTopBar(stringResource(R.string.settings_diagnostics)) { route = Route.Main(current.credentials, ShellTab.Settings) }
                Box(Modifier.weight(1f)) { DiagnosticsScreen() }
            }
        }

        is Route.NetworkDiagnostics -> {
            if (LocalIsTelevision.current) NetworkDiagnosticsScreen(AccountIdentity.of(current.credentials), current.credentials)
            else Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                ScreenTopBar(stringResource(R.string.network_diagnostics_title)) { route = Route.Main(current.credentials, ShellTab.Settings) }
                Box(Modifier.weight(1f)) { NetworkDiagnosticsScreen(AccountIdentity.of(current.credentials), current.credentials) }
            }
        }

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
                    detailOriginTab = when (current.type) {
                        ContentType.MOVIES -> ShellTab.Movies
                        ContentType.SERIES -> ShellTab.Series
                        ContentType.LIVE -> ShellTab.Live
                    }
                    route = if (isSeries) Route.SeriesDetail(current.credentials, item.id)
                        else Route.ItemDetail(current.credentials, current.type, item.id)
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
                        title = item.title,
                        extension = item.extension,
                        resumeFromMs = resumeFromMs,
                        knownDurationMs = item.knownDurationMs
                    )
                }
            )
        }

        is Route.ItemDetail -> {
            val accountId = remember(current.credentials) {
                AccountIdentity.of(current.credentials)
            }
            val detailIsLive = current.type == ContentType.LIVE
            val page: @Composable () -> Unit = { ItemDetailScreen(
                contentType = current.type,
                itemId = current.itemId,
                insideShell = !LocalIsTelevision.current,
                onPlay = { resumeFromMs, extension, title, knownDurationMs ->
                    playItem(
                        context = context,
                        credentials = current.credentials,
                        accountId = accountId,
                        isLive = detailIsLive,
                        itemId = current.itemId,
                        title = title,
                        extension = extension,
                        resumeFromMs = resumeFromMs,
                        knownDurationMs = knownDurationMs
                    )
                },
                // A show's Play is "show me the episodes" — `series_id` addresses no
                // stream endpoint, so the picker is the only thing it can mean.
                onOpenEpisodes = {
                    route = Route.SeriesDetail(current.credentials, current.itemId)
                }
            ) }
            if (LocalIsTelevision.current) page() else AppShell(
                selected = detailOriginTab,
                onSelect = { tab -> savedTab = tab; route = Route.Main(current.credentials, tab) },
                showPhoneTopBar = false,
            ) {
                Column(Modifier.fillMaxSize()) {
                    ScreenTopBar(stringResource(if (detailIsLive) R.string.nav_live_tv else R.string.nav_movies)) {
                        route = Route.Main(current.credentials, detailOriginTab)
                    }
                    Box(Modifier.weight(1f)) { page() }
                }
            }
        }

        is Route.SeriesDetail -> {
            val accountId = remember(current.credentials) {
                AccountIdentity.of(current.credentials)
            }
            val page: @Composable () -> Unit = { SeriesDetailScreen(
                seriesId = current.seriesId,
                insideShell = !LocalIsTelevision.current,
                onPlayEpisode = { episode, resumeFromMs, episodes ->
                    if (DeepStreamCheckGate.isHeld) {
                        android.widget.Toast.makeText(context, R.string.network_check_in_progress, android.widget.Toast.LENGTH_SHORT).show()
                        return@SeriesDetailScreen
                    }
                    // Episodes live on their own path segment and carry their own id —
                    // a string, straight from the panel, never round-tripped through Int.
                    val url = StreamUrlBuilder.episode(
                        current.credentials,
                        episode.episodeId,
                        episode.containerExtension ?: "mkv"
                    )
                    val queue = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNum }))
                    context.startActivity(
                        Intent(context, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_URL, url)
                            .putExtra(PlayerActivity.EXTRA_IS_LIVE, false)
                            .putExtra(PlayerActivity.EXTRA_ITEM_ID, episode.episodeId)
                            .putExtra(
                                PlayerActivity.EXTRA_TITLE,
                                "S${episode.seasonNumber} · E${episode.episodeNum}: ${episode.title}"
                            )
                            .putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, TYPE_SERIES)
                            .putExtra(PlayerActivity.EXTRA_ACCOUNT_ID, accountId)
                            .putExtra(PlayerActivity.EXTRA_RESUME_FROM_MS, resumeFromMs)
                            .putExtra(PlayerActivity.EXTRA_KNOWN_DURATION_MS,
                                episode.durationSecs?.takeIf { it > 0 }?.toLong()?.times(1000L) ?: 0L)
                            .putExtra(PlayerActivity.EXTRA_REQUEST_ID, java.util.UUID.randomUUID().toString())
                            .putStringArrayListExtra(PlayerActivity.EXTRA_EPISODE_IDS, ArrayList(queue.map { it.episodeId }))
                            .putStringArrayListExtra(PlayerActivity.EXTRA_EPISODE_URLS, ArrayList(queue.map {
                                StreamUrlBuilder.episode(current.credentials, it.episodeId, it.containerExtension ?: "mkv")
                            }))
                            .putStringArrayListExtra(PlayerActivity.EXTRA_EPISODE_TITLES, ArrayList(queue.map {
                                "S${it.seasonNumber} · E${it.episodeNum}: ${it.title}"
                            }))
                            .putExtra(PlayerActivity.EXTRA_EPISODE_SEASONS, queue.map { it.seasonNumber }.toIntArray())
                            // SeriesInfoParser prefers `duration`: provider `duration_secs` can be unreliable.
                            .putExtra(PlayerActivity.EXTRA_EPISODE_DURATIONS, queue.map {
                                it.durationSecs?.takeIf { seconds -> seconds > 0 }?.toLong()?.times(1000L) ?: 0L
                            }.toLongArray())
                            .putExtra(PlayerActivity.EXTRA_EPISODE_INDEX, queue.indexOfFirst { it.episodeId == episode.episodeId })
                    )
                    (context as? ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                        AppDatabase.get(context).recentlyPlayedDao().record(
                            RecentlyPlayedEntity(
                                profileId = accountId,
                                accountKey = accountId,
                                contentType = TYPE_SERIES,
                                itemId = current.seriesId.toString(),
                                playedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            ) }
            if (LocalIsTelevision.current) page() else AppShell(
                selected = detailOriginTab,
                onSelect = { tab -> savedTab = tab; route = Route.Main(current.credentials, tab) },
                showPhoneTopBar = false,
            ) {
                Column(Modifier.fillMaxSize()) {
                    ScreenTopBar(stringResource(R.string.nav_series)) {
                        route = Route.Main(current.credentials, detailOriginTab)
                    }
                    Box(Modifier.weight(1f)) { page() }
                }
            }
        }
    }
}
