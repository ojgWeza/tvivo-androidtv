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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Canvas
import java.awt.Color as AwtColor
import java.awt.event.HierarchyEvent
import javax.swing.SwingUtilities
import java.net.URL
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
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = Background, surface = Surface, primary = Accent, onBackground = Ink, onSurface = Ink)) {
        Column(Modifier.fillMaxSize().background(Background)) {
            TopNavigation(route, onRoute = { route = it })
            when (val current = route) {
                DesktopRoute.Home -> HomeScreen(repository) { route = DesktopRoute.Browse(it) }
                is DesktopRoute.Browse -> BrowseScreen(repository, current.type, onDetail = { route = if (it.type == CatalogType.SERIES) DesktopRoute.Episodes(it) else DesktopRoute.Detail(it) })
                is DesktopRoute.Detail -> DetailScreen(repository, current.item, onPlay = { route = DesktopRoute.Player(current.item) }, onBack = { route = DesktopRoute.Browse(current.item.type) })
                is DesktopRoute.Episodes -> EpisodeScreen(repository, current.item, current.season, onPlay = { episode, season -> route = DesktopRoute.Player(current.item, episode, season) }, onBack = { route = DesktopRoute.Browse(CatalogType.SERIES) })
                is DesktopRoute.Player -> DesktopPlayerScreen(repository, current.item, current.episode, onBack = { route = if (current.item.type == CatalogType.SERIES) DesktopRoute.Episodes(current.item, current.returnSeason) else DesktopRoute.Detail(current.item) })
                DesktopRoute.Account -> AccountScreen(repository, credentials, onSignOut = onSignOut)
            }
        }
    }
}

@Composable private fun TopNavigation(route: DesktopRoute, onRoute: (DesktopRoute) -> Unit) = Row(Modifier.fillMaxWidth().height(56.dp).background(Surface).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("TVIVO", color = Ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 18.dp))
    NavButton("Home", Icons.Default.Home) { onRoute(DesktopRoute.Home) }
    NavButton("Movies", Icons.Default.Movie) { onRoute(DesktopRoute.Browse(CatalogType.MOVIES)) }
    NavButton("Series", Icons.Default.Tv) { onRoute(DesktopRoute.Browse(CatalogType.SERIES)) }
    NavButton("Live TV", Icons.Default.LiveTv) { onRoute(DesktopRoute.Browse(CatalogType.LIVE)) }
    Box(Modifier.weight(1f))
    NavButton("Account", Icons.Default.AccountCircle) { onRoute(DesktopRoute.Account) }
}

@Composable private fun NavButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) = OutlinedButton(onClick = onClick) { Icon(icon, contentDescription = label); Text(label, modifier = Modifier.padding(start = 6.dp)) }

@Composable private fun HomeScreen(repository: DesktopCatalogRepository, onBrowse: (CatalogType) -> Unit) {
    val scope = rememberCoroutineScope(); var refreshMessage by remember { mutableStateOf("Loading your library…") }; var artwork by remember { mutableStateOf(emptyMap<CatalogType, String?>()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { withContext(Dispatchers.IO) { CatalogType.entries.forEach { repository.ensureLoaded(it) }; CatalogType.entries.associateWith { repository.items(it, "__recent", "").firstOrNull()?.artwork } } }.onSuccess { artwork = it; refreshMessage = "" }.onFailure { refreshMessage = "Library unavailable. Use Refresh library to try again." } }
    Column(Modifier.fillMaxSize().padding(42.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Your library", style = MaterialTheme.typography.headlineLarge, color = Ink); Text("Choose what you want to watch.", color = Dim, modifier = Modifier.padding(top = 6.dp)) }; Button(onClick = { scope.launch { refreshMessage = "Refreshing library…"; runCatching { withContext(Dispatchers.IO) { CatalogType.entries.forEach { repository.refresh(it) } } }.onSuccess { refreshMessage = "Library refreshed." }.onFailure { refreshMessage = it.message ?: "Refresh failed; cached items are still available." } } }) { Icon(Icons.Default.Refresh, contentDescription = null); Text("Refresh library", modifier = Modifier.padding(start = 6.dp)) } }
    if (refreshMessage.isNotBlank()) Text(refreshMessage, color = Dim, modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CatalogType.entries.forEach { type -> Card(Modifier.weight(1f).height(230.dp).clickable { onBrowse(type) }.border(1.dp, Line)) { Box(Modifier.fillMaxSize()) { ArtworkImage(artwork[type], Modifier.fillMaxSize(), homeTile(type)); Column(Modifier.fillMaxSize().background(Color(0x990A1619)).padding(22.dp), verticalArrangement = Arrangement.Bottom) { Text(type.title, color = Ink, style = MaterialTheme.typography.headlineSmall); Text(if (type == CatalogType.LIVE) "Channels and live events" else "Browse by category", color = Dim) } } } }
    }
    }
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
@Composable private fun CatalogCard(item: DesktopItem, onClick: () -> Unit) = Card(Modifier.height(if (item.type == CatalogType.LIVE) 112.dp else 200.dp).clickable(onClick = onClick).border(1.dp, Line)) { Box(Modifier.fillMaxSize()) { ArtworkImage(item.artwork, Modifier.fillMaxSize()); Column(Modifier.fillMaxSize().background(Color(0xAA0A1619)).padding(12.dp), verticalArrangement = Arrangement.Bottom) { Text(item.title, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(item.rating.positiveRating()?.let { "Rating $it" } ?: item.type.title, color = Dim, style = MaterialTheme.typography.labelSmall) } } }

private fun homeTile(type: CatalogType) = when (type) { CatalogType.LIVE -> "tile_live.jpg"; CatalogType.MOVIES -> "tile_movies.jpg"; CatalogType.SERIES -> "tile_series.jpg" }
@Composable private fun ArtworkImage(url: String?, modifier: Modifier, fallbackResource: String? = null) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(url, fallbackResource) { bitmap = url?.takeIf { it.isNotBlank() }?.let { value -> runCatching { withContext(Dispatchers.IO) { SkiaImage.makeFromEncoded(URL(value).readBytes()).toComposeImageBitmap() } }.getOrNull() } ?: fallbackResource?.let { name -> runCatching { Thread.currentThread().contextClassLoader.getResourceAsStream(name)?.use { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() } }.getOrNull() } }
    Box(modifier.background(Elevated)) { bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } }
}

@Composable private fun DetailScreen(repository: DesktopCatalogRepository, item: DesktopItem, onPlay: () -> Unit, onBack: () -> Unit) { val scope = rememberCoroutineScope(); Column(Modifier.fillMaxSize().padding(42.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) { Text(item.title, color = Ink, style = MaterialTheme.typography.headlineLarge); Text(item.plot ?: "No description is available from your provider.", color = Dim, modifier = Modifier.widthIn(max = 720.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = onPlay) { Text("Play") }; OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { repository.toggleFavourite(item) } }) { Text("Add or remove favourite") }; OutlinedButton(onClick = onBack) { Text("Back") } } } }
@Composable private fun EpisodeScreen(repository: DesktopCatalogRepository, item: DesktopItem, restoredSeason: String?, onPlay: (DesktopEpisode, String) -> Unit, onBack: () -> Unit) { val scope = rememberCoroutineScope(); var episodes by remember(item.id) { mutableStateOf(emptyList<DesktopEpisode>()) }; var selectedSeason by remember(item.id) { mutableStateOf(restoredSeason) }; var seasonMenuOpen by remember { mutableStateOf(false) }; var message by remember(item.id) { mutableStateOf("Loading episodes…") }; androidx.compose.runtime.LaunchedEffect(item.id) { runCatching { withContext(Dispatchers.IO) { repository.episodes(item) } }.onSuccess { loaded -> episodes = loaded; selectedSeason = selectedSeason?.takeIf { season -> loaded.any { it.season == season } } ?: loaded.firstOrNull()?.season; message = if (loaded.isEmpty()) "No episodes are currently available." else "Select an episode to play." }.onFailure { message = it.message ?: "Episodes could not be loaded." } }; val seasons = episodes.map { it.season }.distinct(); val visibleEpisodes = episodes.filter { it.season == selectedSeason }; Column(Modifier.fillMaxSize().padding(42.dp)) { Text(item.title, color = Ink, style = MaterialTheme.typography.headlineLarge); Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) { Box { OutlinedButton(onClick = { seasonMenuOpen = true }, enabled = seasons.isNotEmpty()) { Text(selectedSeason?.let { "Season $it" } ?: "Choose season") }; DropdownMenu(expanded = seasonMenuOpen, onDismissRequest = { seasonMenuOpen = false }) { seasons.forEach { season -> DropdownMenuItem(text = { Text("Season $season") }, onClick = { selectedSeason = season; seasonMenuOpen = false }) } } }; OutlinedButton(onClick = onBack) { Text("Back") } }; Text(message, color = Dim, modifier = Modifier.padding(vertical = 12.dp)); LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) { items(visibleEpisodes, key = { it.id }) { episode -> val label = episode.displayLabel(item.title); OutlinedButton(onClick = { onPlay(episode, selectedSeason ?: episode.season) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, contentDescription = "Play $label"); Text("$label${episode.duration?.let { " · $it" } ?: ""}${episode.resumeMs.takeIf { it > 0 }?.let { " · Resume ${formatPosition(it)}" } ?: ""}", modifier = Modifier.padding(start = 8.dp)) } } } } }

private fun String.removePrefixIgnoreCase(prefix: String): String = if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
private fun String?.positiveRating(): String? = this?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
private fun DesktopEpisode.displayLabel(seriesTitle: String): String { val number = episodeNumber?.takeIf { it.isNotBlank() } ?: id; val remainder = title.removePrefixIgnoreCase("Season $season").trimStart(' ', '-', '·', ':').removePrefixIgnoreCase(seriesTitle).trimStart(' ', '-', '·', ':'); return when { remainder.isBlank() || remainder.equals("Episode $number", ignoreCase = true) -> "Episode $number"; else -> "Episode $number · $remainder" } }
@Composable private fun AccountScreen(repository: DesktopCatalogRepository, credentials: Credentials, onSignOut: () -> Unit) { val scope = rememberCoroutineScope(); var info by remember { mutableStateOf("Loading account…") }; androidx.compose.runtime.LaunchedEffect(Unit) { scope.launch { runCatching { withContext(Dispatchers.IO) { repository.accountInfo() } }.onSuccess { info = "Status: ${it.status ?: "Unknown"}\nExpiry: ${it.expires ?: "Not supplied"}\nMaximum connections: ${it.maxConnections ?: "Not supplied"}" }.onFailure { info = "Account details unavailable offline." } } }; Column(Modifier.fillMaxSize().padding(42.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { Text("Account", color = Ink, style = MaterialTheme.typography.headlineLarge); Text(credentials.hostAndPort(), color = Dim); Text(info, color = Ink); OutlinedButton(onClick = onSignOut) { Text("Sign out") } } }
@Composable private fun DesktopPlayerScreen(repository: DesktopCatalogRepository, item: DesktopItem, episode: DesktopEpisode?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope(); var state by remember { mutableStateOf("Preparing player…") }; var playerReady by remember { mutableStateOf(false) }
    val surface = remember { Canvas().apply { background = AwtColor.BLACK } }
    val player = remember { LibVlcPlayer { update -> SwingUtilities.invokeLater { state = update } } }
    DisposableEffect(player) {
        var attempted = false
        fun initialise() { if (!attempted && surface.isDisplayable) { attempted = true; player.initialise(surface).onSuccess { playerReady = true }.onFailure { state = "LibVLC needs setup: ${it.message}" } } }
        val listener = java.awt.event.HierarchyListener { event -> if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) initialise() }
        surface.addHierarchyListener(listener); initialise()
        onDispose { val position = player.positionMs(); if (position > 0L) repository.recordResume(item, episode, position); surface.removeHierarchyListener(listener); player.close() }
    }
    androidx.compose.runtime.LaunchedEffect(item.id, episode?.id, playerReady) { if (playerReady) scope.launch { state = "Preparing stream…"; runCatching { withContext(Dispatchers.IO) { repository.playbackUrl(item, episode) to repository.resumePosition(if (episode == null) item.type else CatalogType.SERIES, episode?.id ?: item.id) } }.onSuccess { (url, resumeMs) -> player.playUrl(url, resumeMs).onFailure { state = "Playback error: ${it.message}" } }.onFailure { state = it.message ?: "Unable to prepare playback." } } }
    androidx.compose.runtime.LaunchedEffect(item.id, episode?.id, playerReady) { while (playerReady) { delay(5_000); val position = player.positionMs(); if (position > 0L) withContext(Dispatchers.IO) { repository.recordResume(item, episode, position) } } }
    var paused by remember { mutableStateOf(false) }; var seek by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(episode?.let { "${item.title} · ${it.displayLabel(item.title)}" } ?: item.title, color = Ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); Text(state, color = Dim) }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) { SwingPanel(factory = { surface }, modifier = Modifier.fillMaxSize()) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = playerReady, onClick = { if (paused) { player.resume(); paused = false } else { player.pause(); paused = true } }) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = if (paused) "Play" else "Pause"); Text(if (paused) "Play" else "Pause", modifier = Modifier.padding(start = 6.dp)) }
            OutlinedButton(enabled = playerReady, onClick = { player.stop(); state = "Stopped" }) { Text("Stop") }
            Slider(value = seek, onValueChange = { seek = it }, onValueChangeFinished = { if (playerReady) player.seek(seek) }, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { java.awt.Window.getWindows().filterIsInstance<java.awt.Frame>().firstOrNull { it.isActive }?.let { frame -> frame.extendedState = if (frame.extendedState == java.awt.Frame.MAXIMIZED_BOTH) java.awt.Frame.NORMAL else java.awt.Frame.MAXIMIZED_BOTH } }) { Text("Full screen") }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

private fun formatPosition(positionMs: Long): String { val seconds = positionMs / 1_000; return "%d:%02d".format(seconds / 60, seconds % 60) }
