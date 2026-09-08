package com.dev.Tvivo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.auth.LoginScreen
import com.dev.Tvivo.diagnostics.CodecProbe
import com.dev.Tvivo.ui.browse.BrowseScreen
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.home.HomeScreen
import com.dev.Tvivo.ui.settings.AccountViewModel
import com.dev.Tvivo.ui.settings.SettingsScreen
import com.dev.Tvivo.ui.player.PlayerActivity
import com.dev.Tvivo.data.StreamUrlBuilder
import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import android.content.Intent
import com.dev.Tvivo.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) CodecProbe.log()
        setContent {
            MaterialTheme {
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
    data object Loading : Route
    data object Login : Route
    data class Home(val credentials: Credentials) : Route
    data class Browse(val credentials: Credentials, val type: ContentType) : Route
    data class Settings(val credentials: Credentials) : Route
}

@Composable
private fun TvivoApp() {
    val context = LocalContext.current
    val store = remember { CredentialsStore(context.applicationContext) }
    var route by remember { mutableStateOf<Route>(Route.Loading) }

    // Credentials are entered once; every later launch resolves them off the main
    // thread and lands straight on Home.
    LaunchedEffect(Unit) {
        route = store.load()?.let { Route.Home(it) } ?: Route.Login
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
            route is Route.Settings ||
            (route is Route.Login && switchingAccount != null)
    ) {
        when (val current = route) {
            is Route.Browse -> route = Route.Home(current.credentials)
            is Route.Settings -> route = Route.Home(current.credentials)
            is Route.Login -> switchingAccount?.let {
                switchingAccount = null
                route = Route.Home(it)
            }
            else -> Unit
        }
    }

    when (val current = route) {
        Route.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Tvivo", color = Palette.Ink)
        }

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
            onSelect = { type ->
                lastOpened = type
                route = Route.Browse(current.credentials, type)
            },
            onOpenSettings = { route = Route.Settings(current.credentials) }
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
            onExit = { (context as? android.app.Activity)?.finish() }
        )

        // Series is Phase 4. Routing it to the movies repository would show a grid of
        // films under a Series header, which is worse than saying so.
        is Route.Browse -> if (current.type == ContentType.SERIES) {
            ComingSoon("Series")
        } else {
            val isLive = current.type == ContentType.LIVE
            val accountId = remember(current.credentials) {
                AccountIdentity.of(current.credentials)
            }
            BrowseScreen(
                contentType = current.type,
                onPlay = { item, resumeFromMs ->
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
    }
}

@Composable
private fun ComingSoon(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "$label is not built yet. Press Back.", color = Palette.Dim)
    }
}
