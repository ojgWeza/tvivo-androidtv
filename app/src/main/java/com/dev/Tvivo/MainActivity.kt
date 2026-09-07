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

    // Browse is the only screen with somewhere to go back to; Home and Login are the
    // top of the stack and fall through to the default (exit-to-launcher) behaviour.
    BackHandler(enabled = route is Route.Browse) {
        (route as? Route.Browse)?.let { route = Route.Home(it.credentials) }
    }

    when (val current = route) {
        Route.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Tvivo", color = Palette.Ink)
        }

        Route.Login -> LoginScreen(
            onAuthenticated = { account -> route = Route.Home(account.credentials) }
        )

        is Route.Home -> HomeScreen(
            lastSelected = lastOpened,
            onSelect = { type ->
                lastOpened = type
                route = Route.Browse(current.credentials, type)
            }
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
