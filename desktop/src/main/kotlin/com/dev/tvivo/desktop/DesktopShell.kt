package com.dev.tvivo.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import com.dev.Tvivo.auth.Credentials
import com.dev.tvivo.desktop.catalog.CatalogType
import com.dev.tvivo.desktop.catalog.DesktopCatalogRepository
import com.dev.tvivo.desktop.catalog.DesktopEpisode
import com.dev.tvivo.desktop.catalog.DesktopItem
import com.dev.tvivo.desktop.catalog.DesktopSeriesResume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Canvas
import java.awt.Color as AwtColor
import javax.swing.SwingUtilities
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.jetbrains.skia.Image as SkiaImage

private val Background = Color(0xFF0A1619)
private val Surface = Color(0xFF0F2126)
private val Elevated = Color(0xFF163036)
private val Line = Color(0xFF1E4149)
private val Ink = Color(0xFFE8F1F2)
private val Dim = Color(0xFF93ACB1)
private val Accent = Color(0xFFD97757)

private sealed interface DesktopRoute { data object Home : DesktopRoute; data class Browse(val type: CatalogType) : DesktopRoute; data class Detail(val item: DesktopItem) : DesktopRoute; data class Episodes(val item: DesktopItem, val season: String? = null) : DesktopRoute; data class Player(val item: DesktopItem, val episode: DesktopEpisode? = null, val returnSeason: String? = null) : DesktopRoute; data object Account : DesktopRoute }

@Composable
internal fun DesktopShell(credentials: Credentials, onSignOut: () -> Unit) {
    val repository = remember(credentials) { DesktopCatalogRepository(credentials) }
    var route by remember { mutableStateOf<DesktopRoute>(DesktopRoute.Home) }
    var playerFullScreen by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = Background, surface = Surface, primary = Accent, onBackground = Ink, onSurface = Ink)) {
        Column(Modifier.fillMaxSize().background(Background)) {
            if (!playerFullScreen) TopNavigation(route, onRoute = { route = it })
            when (val current = route) {
                DesktopRoute.Home -> HomeScreen(
                    repository = repository,
                    onBrowse = { route = DesktopRoute.Browse(it) },
                    onPlay = { item, episode -> route = DesktopRoute.Player(item, episode, episode?.season) },
                )
                is DesktopRoute.Browse -> BrowseScreen(repository, current.type, onDetail = { route = if (it.type == CatalogType.SERIES) DesktopRoute.Episodes(it) else DesktopRoute.Detail(it) })
                is DesktopRoute.Detail -> DetailScreen(repository, current.item, onPlay = { route = DesktopRoute.Player(current.item) }, onBack = { route = DesktopRoute.Browse(current.item.type) })
                is DesktopRoute.Episodes -> EpisodeScreen(repository, current.item, current.season, onPlay = { episode, season -> route = DesktopRoute.Player(current.item, episode, season) }, onBack = { route = DesktopRoute.Browse(CatalogType.SERIES) })
                is DesktopRoute.Player -> DesktopPlayerScreen(
                    repository = repository,
                    item = current.item,
                    episode = current.episode,
                    fullScreen = playerFullScreen,
                    onFullScreenChange = { enabled -> playerFullScreen = enabled },
                    onBack = {
                        if (playerFullScreen) {
                            playerFullScreen = false
                        }
                        route = if (current.item.type == CatalogType.SERIES) DesktopRoute.Episodes(current.item, current.returnSeason) else DesktopRoute.Detail(current.item)
                    },
                )
                DesktopRoute.Account -> AccountScreen(repository, credentials, onSignOut = onSignOut)
            }
        }
    }
}

@Composable private fun TopNavigation(route: DesktopRoute, onRoute: (DesktopRoute) -> Unit) = Row(Modifier.fillMaxWidth().height(64.dp).background(Surface).border(1.dp, Line).padding(horizontal = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("TVIVO", color = Ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 26.dp))
    NavButton("Home", Icons.Default.Home, route is DesktopRoute.Home) { onRoute(DesktopRoute.Home) }
    NavButton("Movies", Icons.Default.Movie, route is DesktopRoute.Browse && route.type == CatalogType.MOVIES) { onRoute(DesktopRoute.Browse(CatalogType.MOVIES)) }
    NavButton("Series", Icons.Default.Tv, route is DesktopRoute.Browse && route.type == CatalogType.SERIES) { onRoute(DesktopRoute.Browse(CatalogType.SERIES)) }
    NavButton("Live TV", Icons.Default.LiveTv, route is DesktopRoute.Browse && route.type == CatalogType.LIVE) { onRoute(DesktopRoute.Browse(CatalogType.LIVE)) }
    Box(Modifier.weight(1f))
    NavButton("Account", Icons.Default.AccountCircle, route is DesktopRoute.Account) { onRoute(DesktopRoute.Account) }
}

@Composable private fun NavButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) = Row(
    Modifier.clip(RoundedCornerShape(9.dp)).background(if (selected) Elevated else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
) { Icon(icon, contentDescription = label, tint = if (selected) Ink else Dim); Text(label, color = if (selected) Ink else Dim, modifier = Modifier.padding(start = 7.dp)) }

@Composable private fun HomeScreen(repository: DesktopCatalogRepository, onBrowse: (CatalogType) -> Unit, onPlay: (DesktopItem, DesktopEpisode?) -> Unit) {
    val scope = rememberCoroutineScope(); var refreshMessage by remember { mutableStateOf("Loading your library…") }
    var live by remember { mutableStateOf(emptyList<DesktopItem>()) }; var movies by remember { mutableStateOf(emptyList<DesktopItem>()) }; var episodes by remember { mutableStateOf(emptyList<DesktopSeriesResume>()) }
    fun loadRecent() { scope.launch { withContext(Dispatchers.IO) { Triple(repository.recentItems(CatalogType.LIVE), repository.recentItems(CatalogType.MOVIES), repository.recentEpisodes()) }.let { (recentLive, recentMovies, recentEpisodes) -> live = recentLive; movies = recentMovies; episodes = recentEpisodes } } }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { withContext(Dispatchers.IO) { CatalogType.entries.forEach { repository.ensureLoaded(it) } } }.onSuccess { refreshMessage = ""; loadRecent() }.onFailure { refreshMessage = "Library unavailable. Use Refresh library to try again." } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 42.dp, vertical = 34.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Welcome back", style = MaterialTheme.typography.headlineLarge, color = Ink); Text("Pick up where you left off, or find something new.", color = Dim, modifier = Modifier.padding(top = 6.dp)) }; Button(onClick = { scope.launch { refreshMessage = "Refreshing library…"; runCatching { withContext(Dispatchers.IO) { CatalogType.entries.forEach { repository.refresh(it) } } }.onSuccess { refreshMessage = "Library refreshed." }.onFailure { refreshMessage = it.message ?: "Refresh failed; cached items are still available." } } }) { Icon(Icons.Default.Refresh, contentDescription = null); Text("Refresh", modifier = Modifier.padding(start = 6.dp)) } }
    if (refreshMessage.isNotBlank()) Text(refreshMessage, color = Dim, modifier = Modifier.padding(top = 10.dp))
    Box(Modifier.fillMaxWidth().height(250.dp).padding(top = 28.dp).clip(RoundedCornerShape(18.dp)).background(Elevated).clickable { onBrowse(CatalogType.MOVIES) }) {
        ArtworkImage(null, Modifier.fillMaxSize(), homeTile(CatalogType.MOVIES))
        Column(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xED0A1619), Color(0xAA0A1619), Color.Transparent))).padding(30.dp), verticalArrangement = Arrangement.Bottom) { Text("Tonight’s movie shelf", color = Ink, style = MaterialTheme.typography.headlineLarge); Text("Browse films by category, favourites, and recently added.", color = Dim, modifier = Modifier.padding(top = 7.dp)); Text("Explore movies", color = Accent, modifier = Modifier.padding(top = 16.dp)) }
    }
    HomeShelf("Continue with Live TV", live, onBrowse = { onBrowse(CatalogType.LIVE) }, onPlay = { onPlay(it, null) })
    HomeShelf("Continue watching movies", movies, onBrowse = { onBrowse(CatalogType.MOVIES) }, onPlay = { onPlay(it, null) })
    EpisodeHomeShelf(episodes, onBrowse = { onBrowse(CatalogType.SERIES) }, onPlay = onPlay)
    Text("Explore", color = Ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 30.dp, bottom = 14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CatalogType.entries.forEach { type -> Box(Modifier.weight(1f).height(172.dp).clip(RoundedCornerShape(14.dp)).background(Elevated).border(1.dp, Line, RoundedCornerShape(14.dp)).clickable { onBrowse(type) }) { ArtworkImage(null, Modifier.fillMaxSize(), homeTile(type)); Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE80A1619)))).padding(18.dp), verticalArrangement = Arrangement.Bottom) { Text(type.title, color = Ink, style = MaterialTheme.typography.titleLarge); Text(if (type == CatalogType.LIVE) "Channels and live events" else "Browse your library", color = Dim, modifier = Modifier.padding(top = 3.dp)) } } }
    }
    }
}

@Composable private fun HomeShelf(title: String, items: List<DesktopItem>, onBrowse: () -> Unit, onPlay: (DesktopItem) -> Unit) {
    Text(title, color = Ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 30.dp, bottom = 14.dp))
    if (items.isEmpty()) { Text("Play something from your library and it will appear here.", color = Dim) } else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items.forEach { item -> Box(Modifier.width(190.dp)) { CatalogCard(item) { onPlay(item) } } }; Text("See all", color = Accent, modifier = Modifier.align(Alignment.CenterVertically).clickable(onClick = onBrowse).padding(12.dp)) }
}

@Composable private fun EpisodeHomeShelf(items: List<DesktopSeriesResume>, onBrowse: () -> Unit, onPlay: (DesktopItem, DesktopEpisode) -> Unit) {
    Text("Continue watching series", color = Ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 30.dp, bottom = 14.dp))
    if (items.isEmpty()) { Text("Your last episodes will appear here.", color = Dim) } else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items.forEach { resume -> Box(Modifier.width(190.dp).height(184.dp).clip(RoundedCornerShape(12.dp)).background(Elevated).border(1.dp, Line, RoundedCornerShape(12.dp)).clickable { onPlay(resume.series, resume.episode) }) { ArtworkImage(resume.series.artwork, Modifier.fillMaxSize()); Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE0A1619)))).padding(12.dp), verticalArrangement = Arrangement.Bottom) { Text(resume.series.title, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("S${resume.episode.season.padStart(2, '0')} E${(resume.episode.episodeNumber ?: "?").padStart(2, '0')}", color = Accent, modifier = Modifier.padding(top = 4.dp)) } } }; Text("See all", color = Accent, modifier = Modifier.align(Alignment.CenterVertically).clickable(onClick = onBrowse).padding(12.dp)) }
}

@Composable private fun BrowseScreen(repository: DesktopCatalogRepository, type: CatalogType, onDetail: (DesktopItem) -> Unit) {
    val scope = rememberCoroutineScope(); var categories by remember(type) { mutableStateOf(emptyList<com.dev.tvivo.desktop.catalog.DesktopCategory>()) }; var selected by remember(type) { mutableStateOf("__all") }; var categoryFilter by remember(type) { mutableStateOf("") }; var query by remember(type) { mutableStateOf("") }; var catalogItems by remember(type, selected, query) { mutableStateOf(emptyList<DesktopItem>()) }; var message by remember(type) { mutableStateOf("Refresh to download your ${type.title.lowercase()} library.") }
    fun load() { scope.launch { runCatching { withContext(Dispatchers.IO) { repository.ensureLoaded(type); repository.categories(type) to repository.items(type, selected, query) } }.onSuccess { (cats, rows) -> categories = cats; catalogItems = rows; message = if (rows.isNotEmpty()) "${rows.size} items" else "No items available for this selection." }.onFailure { message = it.message ?: "Library unavailable offline." } } }
    androidx.compose.runtime.LaunchedEffect(type, selected, query) { load() }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(220.dp).fillMaxHeight().background(Surface).padding(12.dp)) {
            OutlinedTextField(categoryFilter, { categoryFilter = it }, label = { Text("Filter categories") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            listOf("__all" to "All ${type.title}", "__recent" to "Recently added", "__continue" to "Continue watching", "__favourites" to "Favourites").forEach { (id, label) -> RailButton(label, selected == id) { selected = id } }
            HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn { items(categories.filter { it.name.contains(categoryFilter, ignoreCase = true) }, key = { it.id }) { category -> RailButton(category.name, selected == category.id) { selected = category.id } } }
        }
        Column(Modifier.weight(1f).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(type.title, style = MaterialTheme.typography.headlineMedium, color = Ink); Box(Modifier.weight(1f)); OutlinedTextField(query, { query = it }, label = { Text("Search ${type.title}") }, singleLine = true); Button(onClick = { scope.launch { message = "Refreshing…"; runCatching { withContext(Dispatchers.IO) { repository.refresh(type) } }.onSuccess { load(); message = "Library refreshed." }.onFailure { message = it.message ?: "Refresh failed." } } }, modifier = Modifier.padding(start = 10.dp)) { Text("Refresh") } }
            Text(message, color = Dim, modifier = Modifier.padding(vertical = 10.dp))
            LazyVerticalGrid(GridCells.Adaptive(if (type == CatalogType.LIVE) 170.dp else 140.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) { items(catalogItems, key = { it.id }) { item -> CatalogCard(item) { onDetail(item) } } }
        }
    }
}

@Composable private fun RailButton(label: String, selected: Boolean, onClick: () -> Unit) = Text(label, color = if (selected) Color(0xFF1A0A05) else Ink, maxLines = 2, overflow = TextOverflow.Clip, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).background(if (selected) Accent else Color.Transparent).clickable(onClick = onClick).padding(10.dp))
@Composable private fun CatalogCard(item: DesktopItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.height(if (item.type == CatalogType.LIVE) 112.dp else 200.dp).clip(shape).background(Elevated).border(1.dp, Line, shape).clickable(onClick = onClick),
    ) {
        ArtworkImage(item.artwork, Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE0A1619)))).padding(12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(item.title, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.rating.positiveRating()?.let { "Rating $it" } ?: item.type.title, color = Dim, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun homeTile(type: CatalogType) = when (type) { CatalogType.LIVE -> "tile_live.jpg"; CatalogType.MOVIES -> "tile_movies.jpg"; CatalogType.SERIES -> "tile_series.jpg" }
@Composable private fun ArtworkImage(url: String?, modifier: Modifier, fallbackResource: String? = null) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(url, fallbackResource) { bitmap = url?.takeIf { it.isNotBlank() }?.let { value -> runCatching { withContext(Dispatchers.IO) { SkiaImage.makeFromEncoded(URL(value).readBytes()).toComposeImageBitmap() } }.getOrNull() } ?: fallbackResource?.let { name -> runCatching { Thread.currentThread().contextClassLoader.getResourceAsStream(name)?.use { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() } }.getOrNull() } }
    Box(modifier.background(Elevated)) { bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } }
}

@Composable private fun DetailScreen(repository: DesktopCatalogRepository, item: DesktopItem, onPlay: () -> Unit, onBack: () -> Unit) { val scope = rememberCoroutineScope(); Column(Modifier.fillMaxSize().padding(42.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) { Text(item.title, color = Ink, style = MaterialTheme.typography.headlineLarge); Text(item.plot ?: "No description is available from your provider.", color = Dim, modifier = Modifier.widthIn(max = 720.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = onPlay) { Text("Play") }; OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { repository.toggleFavourite(item) } }) { Text("Add or remove favourite") }; OutlinedButton(onClick = onBack) { Text("Back") } } } }
@Composable private fun EpisodeScreen(repository: DesktopCatalogRepository, item: DesktopItem, restoredSeason: String?, onPlay: (DesktopEpisode, String) -> Unit, onBack: () -> Unit) { val scope = rememberCoroutineScope(); var episodes by remember(item.id) { mutableStateOf(emptyList<DesktopEpisode>()) }; var selectedSeason by remember(item.id) { mutableStateOf(restoredSeason) }; var seasonMenuOpen by remember { mutableStateOf(false) }; var message by remember(item.id) { mutableStateOf("Loading episodes…") }; androidx.compose.runtime.LaunchedEffect(item.id) { runCatching { withContext(Dispatchers.IO) { repository.episodes(item) } }.onSuccess { loaded -> episodes = loaded; selectedSeason = selectedSeason?.takeIf { season -> loaded.any { it.season == season } } ?: loaded.firstOrNull()?.season; message = if (loaded.isEmpty()) "No episodes are currently available." else "Choose an episode to start playback." }.onFailure { message = it.message ?: "Episodes could not be loaded." } }; val seasons = episodes.map { it.season }.distinct(); val visibleEpisodes = episodes.filter { it.season == selectedSeason }; Column(Modifier.fillMaxSize().padding(42.dp)) { Text(item.title, color = Ink, style = MaterialTheme.typography.headlineLarge); Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) { Box { Button(onClick = { seasonMenuOpen = true }, enabled = seasons.isNotEmpty()) { Text(selectedSeason?.let { "Season $it" } ?: "Choose season"); Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose season") }; DropdownMenu(expanded = seasonMenuOpen, onDismissRequest = { seasonMenuOpen = false }) { seasons.forEach { season -> DropdownMenuItem(text = { Text("Season $season") }, onClick = { selectedSeason = season; seasonMenuOpen = false }) } } }; OutlinedButton(onClick = onBack) { Text("Back") } }; Text(message, color = Dim, modifier = Modifier.padding(vertical = 12.dp)); LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) { items(visibleEpisodes, key = { it.id }) { episode -> val label = episode.displayLabel(item.title); Button(onClick = { onPlay(episode, selectedSeason ?: episode.season) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, contentDescription = "Play $label"); Text("Play $label${episode.duration?.let { " · $it" } ?: ""}${episode.resumeMs.takeIf { it > 0 }?.let { " · Resume ${formatPosition(it)}" } ?: ""}", modifier = Modifier.padding(start = 8.dp)) } } } } }

private fun String.removePrefixIgnoreCase(prefix: String): String = if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
private fun String?.positiveRating(): String? = this?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
private fun DesktopEpisode.displayLabel(seriesTitle: String): String { val number = episodeNumber?.takeIf { it.isNotBlank() } ?: id; val remainder = title.removePrefixIgnoreCase("Season $season").trimStart(' ', '-', '·', ':').removePrefixIgnoreCase(seriesTitle).trimStart(' ', '-', '·', ':'); val normalizedTitle = title.lowercase().filter(Char::isLetterOrDigit); val normalizedSeries = seriesTitle.lowercase().filter(Char::isLetterOrDigit); return when { remainder.isBlank() || remainder.equals("Episode $number", ignoreCase = true) || (normalizedTitle.startsWith(normalizedSeries) && Regex("s\\d{1,2}e\\d{1,3}").containsMatchIn(normalizedTitle)) -> "Episode $number"; else -> "Episode $number · $remainder" } }
private fun String?.expiryDate(): String? = runCatching { val seconds = this?.trim()?.toLongOrNull()?.takeIf { it in 1..253402300799 } ?: return null; DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC)) }.getOrNull()
@Composable private fun AccountScreen(repository: DesktopCatalogRepository, credentials: Credentials, onSignOut: () -> Unit) { val scope = rememberCoroutineScope(); var info by remember { mutableStateOf("Loading account…") }; androidx.compose.runtime.LaunchedEffect(Unit) { scope.launch { runCatching { withContext(Dispatchers.IO) { repository.accountInfo() } }.onSuccess { info = "Status: ${it.status ?: "Unknown"}\nExpiry: ${it.expires.expiryDate() ?: "Not supplied"}\nMaximum connections: ${it.maxConnections ?: "Not supplied"}" }.onFailure { info = "Account details unavailable offline." } } }; Column(Modifier.fillMaxSize().padding(42.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { Text("Account", color = Ink, style = MaterialTheme.typography.headlineLarge); Text(credentials.hostAndPort(), color = Dim); Text(info, color = Ink); OutlinedButton(onClick = onSignOut) { Text("Sign out") } } }
@Composable private fun DesktopPlayerScreen(
    repository: DesktopCatalogRepository,
    item: DesktopItem,
    episode: DesktopEpisode?,
    fullScreen: Boolean,
    onFullScreenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope(); var state by remember { mutableStateOf("Preparing player…") }; var playerReady by remember { mutableStateOf(false) }
    // Generation of the current playback attempt. MpvPlayer bumps its own internal generation on
    // every play()/playUrl() and tags every onState/onPositionMs callback with it -- a late event
    // from an attempt the watchdog already timed out (or the user navigated away from) carries a
    // stale generation and must be ignored rather than overwriting newer state. Mirrors the old
    // `terminal` flag's intent but ties it to the actual attempt instead of one global switch.
    var currentGeneration by remember { mutableStateOf(0) }
    var terminal by remember { mutableStateOf(false) }
    var lastKnownPositionMs by remember { mutableStateOf(0L) }
    // Two consecutive strictly-increasing time-pos samples within the same generation, not merely
    // "state == Playing" or a nonzero resume-seek position -- see D-Desktop-14 review notes.
    var lastObservedPositionForWatchdog by remember { mutableStateOf(-1L) }
    var watchdogSatisfied by remember { mutableStateOf(false) }
    var userPaused by remember { mutableStateOf(false) }
    val surface = remember { Canvas().apply { background = AwtColor.BLACK } }
    val player = remember {
        // Callbacks are the only source of truth for currentGeneration -- rather than DesktopShell
        // separately recording the generation play()/playUrl() returns (which races the owner
        // thread's own invokeLater for the first callback of that same generation, since both are
        // posted from different threads with no ordering guarantee between them), every callback
        // simply advances currentGeneration forward to whatever generation it carries. A callback
        // is only ever dropped if it's *older* than one already rendered -- never because the UI
        // hadn't yet "caught up" to a generation the player already moved past.
        MpvPlayer(
            onState = { generation, update -> SwingUtilities.invokeLater {
                if (generation < currentGeneration || terminal) return@invokeLater
                currentGeneration = generation
                state = update
            } },
            onPositionMs = { generation, positionMs -> SwingUtilities.invokeLater {
                if (generation < currentGeneration || terminal) return@invokeLater
                currentGeneration = generation
                lastKnownPositionMs = positionMs
                if (!watchdogSatisfied && positionMs > 0L && positionMs > lastObservedPositionForWatchdog) {
                    if (lastObservedPositionForWatchdog >= 0L) watchdogSatisfied = true
                    lastObservedPositionForWatchdog = positionMs
                }
            } },
            onPauseChange = { generation, paused -> SwingUtilities.invokeLater {
                if (generation < currentGeneration || terminal) return@invokeLater
                currentGeneration = generation
                userPaused = paused
            } },
        )
    }
    DisposableEffect(player) {
        // initialise() waits on the Canvas becoming displayable and must not run on the Compose/
        // AWT event thread (it would deadlock against its own EventQueue.invokeAndWait) -- launch
        // it on a background thread instead of calling it synchronously here.
        scope.launch(Dispatchers.IO) {
            player.initialise(surface)
                .onSuccess { withContext(Dispatchers.Main) { playerReady = true } }
                .onFailure { val message = it.message; withContext(Dispatchers.Main) { terminal = true; state = "Player setup failed: $message" } }
        }
        onDispose {
            if (lastKnownPositionMs > 0L) repository.recordResume(item, episode, lastKnownPositionMs)
            // close() enqueues a non-blocking terminal task on the player's own owner thread --
            // safe to call from this synchronous disposal callback.
            player.close()
        }
    }
    androidx.compose.runtime.LaunchedEffect(item.id, episode?.id, playerReady) {
        if (!playerReady) return@LaunchedEffect
        val debugFixture = System.getProperty("tvivo.debug.fixture")
        terminal = false
        watchdogSatisfied = false
        lastObservedPositionForWatchdog = -1L
        userPaused = false
        state = "Preparing stream…"
        scope.launch(Dispatchers.IO) {
            if (debugFixture != null) {
                // currentGeneration is advanced only by MpvPlayer's own callbacks (see the
                // MpvPlayer(...) comment above) -- play()'s return value isn't used for that here,
                // only for surfacing a synchronous validation failure (bad file, bad extension).
                player.play(java.io.File(debugFixture))
                    .onFailure { val message = it.message; withContext(Dispatchers.Main) { terminal = true; state = "Playback error: $message" } }
                return@launch
            }
            val prepared = runCatching { repository.playbackUrl(item, episode) to repository.resumePosition(if (episode == null) item.type else CatalogType.SERIES, episode?.id ?: item.id) }
            if (terminal) return@launch
            prepared
                .onSuccess { (url, resumeMs) ->
                    player.playUrl(url, resumeMs)
                        .onFailure { val message = it.message; withContext(Dispatchers.Main) { terminal = true; state = "Playback error: $message" } }
                }
                .onFailure { val message = it.message; withContext(Dispatchers.Main) { terminal = true; state = message ?: "Unable to prepare playback." } }
        }
    }
    // Watchdog: if the stream never shows genuine progress, stop it and surface an actionable
    // error instead of leaving the user stuck on an indefinite "Buffering/Opening" state. Armed
    // per attempt (item/episode/generation). Polls in a loop rather than one delayed check so a
    // pause genuinely suspends the budget instead of the single check landing mid-pause and
    // skipping the timeout forever -- only time spent NOT paused counts toward the 20s budget.
    androidx.compose.runtime.LaunchedEffect(item.id, episode?.id, playerReady, currentGeneration) {
        if (!playerReady || currentGeneration == 0) return@LaunchedEffect
        val verbose = System.getProperty("tvivo.debug.verbose") != null
        val watchdogGeneration = currentGeneration
        if (verbose) System.err.println("[watchdog] armed gen=$watchdogGeneration")
        var activeElapsedMs = 0L
        val pollMs = 500L
        while (activeElapsedMs < 20_000L) {
            delay(pollMs)
            if (terminal || watchdogGeneration != currentGeneration) return@LaunchedEffect
            if (watchdogSatisfied) { if (verbose) System.err.println("[watchdog] satisfied gen=$watchdogGeneration"); return@LaunchedEffect }
            if (!userPaused) activeElapsedMs += pollMs
        }
        if (verbose) System.err.println("[watchdog] checking gen=$watchdogGeneration current=$currentGeneration satisfied=$watchdogSatisfied paused=$userPaused terminal=$terminal")
        if (!terminal && !watchdogSatisfied && watchdogGeneration == currentGeneration) {
            if (verbose) System.err.println("[watchdog] triggering stop+timeout")
            terminal = true
            state = "Playback timed out. The stream did not respond -- check the connection and try again."
            player.stop()
        }
    }
    androidx.compose.runtime.LaunchedEffect(item.id, episode?.id, playerReady) { while (playerReady) { delay(5_000); if (lastKnownPositionMs > 0L) withContext(Dispatchers.IO) { repository.recordResume(item, episode, lastKnownPositionMs) } } }
    val title = episode?.let { "${item.title} · Season ${it.season} · ${it.displayLabel(item.title)}" } ?: item.title
    val outerPadding = if (fullScreen) 0.dp else 24.dp
    // mpv's built-in OSC (enabled in MpvPlayer.initialise) owns Play/Pause/seek inside the video
    // surface and binds its own f/Escape fullscreen toggle -- Compose no longer intercepts those
    // keys here (D-Desktop-14 review: two owners for the same keys inside an embedded native
    // window is a real conflict, not a style choice). mpv's fullscreen is not wired to
    // onFullScreenChange: whether it can promote/demote cleanly against the surrounding Compose
    // window under --wid embedding is unverified, so D-Desktop-9 stays open rather than assuming
    // it works. Back stays a Compose element outside the Canvas, the one control mpv's OSC has no
    // equivalent for.
    Column(Modifier.fillMaxSize().padding(outerPadding), verticalArrangement = Arrangement.spacedBy(if (fullScreen) 0.dp else 16.dp)) {
        Row(Modifier.fillMaxWidth().background(Surface).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).widthIn(min = 180.dp))
            Text(state, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp).padding(start = 16.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) { Text("Back") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) { SwingPanel(factory = { surface }, modifier = Modifier.fillMaxSize()) }
    }
}

private fun formatPosition(positionMs: Long): String { val seconds = positionMs / 1_000; return "%d:%02d".format(seconds / 60, seconds % 60) }
