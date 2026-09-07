package com.dev.Tvivo

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.auth.CredentialsStore
import com.dev.Tvivo.auth.LoginScreen
import com.dev.Tvivo.diagnostics.CodecProbe
import com.dev.Tvivo.ui.browse.BrowseScreen
import com.dev.Tvivo.ui.home.ContentType
import com.dev.Tvivo.ui.home.HomeScreen
import com.dev.Tvivo.ui.player.PlayerActivity
import com.dev.Tvivo.data.StreamUrlBuilder
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
            onSelect = { type -> route = Route.Browse(current.credentials, type) }
        )

        is Route.Browse -> BrowseScreen(
            onPlay = { item ->
                val url = StreamUrlBuilder.movie(
                    current.credentials,
                    item.streamId,
                    item.containerExtension ?: "mp4"
                )
                context.startActivity(
                    Intent(context, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_URL, url)
                        .putExtra(PlayerActivity.EXTRA_IS_LIVE, false)
                )
            }
        )
    }
}
