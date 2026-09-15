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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.jetbrains.skia.Image as SkiaImage

private val Surface = Color(0xFF0F2126)
private val Accent = Color(0xFFD97757)

internal sealed interface DesktopRoute { data object Home : DesktopRoute; data class Browse(val type: CatalogType) : DesktopRoute; data class Detail(val item: DesktopItem) : DesktopRoute; data class Episodes(val item: DesktopItem, val season: String? = null) : DesktopRoute; data class Player(val item: DesktopItem, val episode: DesktopEpisode? = null, val returnSeason: String? = null) : DesktopRoute; data object Account : DesktopRoute }

/**
 * D-Desktop-16b: one instance per [CatalogType], hoisted at [DesktopShell] scope rather than
 * `remember`ed inside [BrowseScreen] -- so it survives leaving Browse for Detail/Episodes/Player
 * and coming back, instead of resetting every time the route is re-entered. Field-level
 * `mutableStateOf` (not a wrapping `MutableState<BrowseSavedState>`) is deliberate: replacing the
 * whole object on every filter/query change would recreate [gridState] too, losing scroll
 * position on the exact edit that's supposed to preserve it.
 */
private class BrowseSavedState {
    var filter by mutableStateOf("__all")
    var categoryFilter by mutableStateOf("")
    var query by mutableStateOf("")
    var highlightedId by mutableStateOf<String?>(null)
    // Keep the last data set mounted with the grid state. On a Browse -> Detail -> Browse
    // round-trip, a transient empty list would otherwise clamp LazyGridState back to index 0
    // before the asynchronous reload completes.
    var items by mutableStateOf(emptyList<DesktopItem>())
    val gridState = androidx.compose.foundation.lazy.grid.LazyGridState()
}

@Composable
internal fun DesktopShell(credentials: Credentials, onSignOut: () -> Unit) {
    val repository = remember(credentials) { DesktopCatalogRepository(credentials) }
    val scope = rememberCoroutineScope()
    val idleController = remember { DesktopIdleController(scope) }
    var route by remember { mutableStateOf<DesktopRoute>(DesktopRoute.Home) }
    var playerFullScreen by remember { mutableStateOf(false) }
    var featuredSeries by remember { mutableStateOf(emptyList<DesktopItem>()) }
    var idleOverlayVisible by remember { mutableStateOf(false) }
    // D-Desktop-16b: keyed by tab so switching Movies -> Series -> Movies doesn't cross-clobber
    // each tab's own filter/scroll/selection.
    val browseStates = remember { mutableMapOf<CatalogType, BrowseSavedState>() }
    fun browseState(type: CatalogType) = browseStates.getOrPut(type) { BrowseSavedState() }

    LaunchedEffect(route) {
        idleController.onRouteChanged(route)
    }
    val idleState by idleController.state
    LaunchedEffect(idleState) {
        if (idleState == IdleState.Idle) idleOverlayVisible = true
        else if (idleOverlayVisible) {
            delay(200)
            idleOverlayVisible = false
        }
    }
    LaunchedEffect(repository) { featuredSeries = runCatching { withContext(Dispatchers.IO) { repository.ensureLoaded(CatalogType.SERIES); repository.regenerateSuggestions(CatalogType.SERIES, emptySet(), limit = 6) } }.getOrElse { emptyList() } }
    DisposableEffect(idleController) {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listener = PropertyChangeListener { idleController.onWindowFocusChanged(it.newValue != null) }
        focusManager.addPropertyChangeListener("activeWindow", listener)
        idleController.onWindowFocusChanged(focusManager.activeWindow != null)
        onDispose {
            focusManager.removePropertyChangeListener("activeWindow", listener)
            idleController.dispose()
        }
    }
    fun onAnyInput(reason: String) = idleController.onInput(reason)

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = Background, surface = Surface, primary = Accent, onBackground = Ink, onSurface = Ink)) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                val consume = idleOverlayVisible
                onAnyInput("keyboard")
                consume
            } else false
        }.pointerInput(idleState) {
            awaitPointerEventScope { while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) {
                    if (onAnyInput("mouse")) event.changes.forEach { it.consume() }
                }
            } }
        }) {
            Column(Modifier.fillMaxSize().background(Background)) {
                if (!playerFullScreen) TopNavigation(route, onRoute = { route = it; onAnyInput("navigation") })
                when (val current = route) {
                    DesktopRoute.Home -> HomeScreen(
                        repository = repository,
                        // D-Desktop-16b: "See all" now carries a typed destination filter (e.g.
                        // __continue, __favourites) instead of always landing on the unfiltered "__all"
                        // browse -- this is what lets a continuation shelf's See all actually open on
                        // the matching virtual folder rather than the full catalog.
                        onBrowse = { type, filter -> browseState(type).filter = filter; route = DesktopRoute.Browse(type); onAnyInput("navigation") },
                        onPlay = { item, episode -> route = DesktopRoute.Player(item, episode, episode?.season); onAnyInput("navigation") },
                    )
                    is DesktopRoute.Browse -> BrowseScreen(repository, current.type, browseState(current.type), onDetail = {
                        // Remember which card was open so returning to this tab highlights it, not just
                        // restores the scroll offset gridState already keeps for free.
                        browseState(current.type).highlightedId = it.id
                        route = if (it.type == CatalogType.SERIES) DesktopRoute.Episodes(it) else DesktopRoute.Detail(it)
                        onAnyInput("navigation")
                    })
                    is DesktopRoute.Detail -> DetailScreen(repository, current.item, onPlay = { route = DesktopRoute.Player(current.item); onAnyInput("navigation") }, onBack = { route = DesktopRoute.Browse(current.item.type); onAnyInput("navigation") })
                    is DesktopRoute.Episodes -> EpisodeScreen(repository, current.item, current.season, onPlay = { episode, season -> route = DesktopRoute.Player(current.item, episode, season); onAnyInput("navigation") }, onBack = { route = DesktopRoute.Browse(CatalogType.SERIES); onAnyInput("navigation") })
                    is DesktopRoute.Player -> DesktopPlayerScreen(
                        repository = repository,
                        item = current.item,
                        episode = current.episode,
                        fullScreen = playerFullScreen,
                        onFullScreenChange = { enabled -> playerFullScreen = enabled; onAnyInput("player-control") },
                        onBack = {
                            if (playerFullScreen) {
                                playerFullScreen = false
                            }
                            route = if (current.item.type == CatalogType.SERIES) DesktopRoute.Episodes(current.item, current.returnSeason) else DesktopRoute.Detail(current.item)
                            onAnyInput("navigation")
                        },
                    )
                    DesktopRoute.Account -> AccountScreen(repository, credentials, onSignOut = onSignOut)
                }
            }

            // D-Desktop-16e: Idle overlay on top of all content
            if (idleOverlayVisible) {
                IdleEntryScreen(
                    items = featuredSeries,
                    onExit = ::onAnyInput,
                )
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

@Composable private fun HomeScreen(repository: DesktopCatalogRepository, onBrowse: (CatalogType, String) -> Unit, onPlay: (DesktopItem, DesktopEpisode?) -> Unit) {
    val scope = rememberCoroutineScope(); var refreshMessage by remember { mutableStateOf("Loading your library…") }
    // D-Desktop-16a: distinguishes "haven't loaded yet" from "loaded and genuinely empty" so the
    // first-run actionable state below doesn't flash before the initial load resolves.
    var loaded by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(emptyList<DesktopItem>()) }; var movies by remember { mutableStateOf(emptyList<DesktopItem>()) }; var episodes by remember { mutableStateOf(emptyList<DesktopSeriesResume>()) }
    var suggestions by remember { mutableStateOf(emptyMap<CatalogType, List<DesktopItem>>()) }
    suspend fun loadRecent(regenerateSuggestions: Boolean = false) { withContext(Dispatchers.IO) {
        val recent = Triple(repository.recentItems(CatalogType.LIVE), repository.recentItems(CatalogType.MOVIES), repository.recentEpisodes())
        val exclusions = mapOf(CatalogType.LIVE to recent.first.map { it.id }.toSet(), CatalogType.MOVIES to recent.second.map { it.id }.toSet(), CatalogType.SERIES to recent.third.map { it.series.id }.toSet())
        recent to CatalogType.entries.associateWith { type -> if (regenerateSuggestions) repository.regenerateSuggestions(type, exclusions.getValue(type)) else repository.ensureSuggestions(type, exclusions.getValue(type)) }
    }.let { (recent, loadedSuggestions) -> live = recent.first; movies = recent.second; episodes = recent.third; suggestions = loadedSuggestions } }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { withContext(Dispatchers.IO) { CatalogType.entries.forEach { repository.ensureLoaded(it) } } }.onSuccess { refreshMessage = ""; loadRecent() }.onFailure { refreshMessage = "Library unavailable. Use Refresh library to try again." }; loaded = true }
    val hasAnyContinuation = live.isNotEmpty() || movies.isNotEmpty() || episodes.isNotEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 42.dp, vertical = 34.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Welcome back", style = MaterialTheme.typography.headlineLarge, color = Ink); Text("Pick up where you left off, or find something new.", color = Dim, modifier = Modifier.padding(top = 6.dp)) }; Button(onClick = { scope.launch { refreshMessage = "Refreshing library…"; runCatching { withContext(Dispatchers.IO) { CatalogType.entries.forEach { repository.refresh(it) } } }.onSuccess { refreshMessage = "Library refreshed."; loadRecent(regenerateSuggestions = true) }.onFailure { refreshMessage = it.message ?: "Refresh failed; cached items are still available." } } }) { Icon(Icons.Default.Refresh, contentDescription = null); Text("Refresh", modifier = Modifier.padding(start = 6.dp)) } }
    if (refreshMessage.isNotBlank()) Text(refreshMessage, color = Dim, modifier = Modifier.padding(top = 10.dp))
    // D-Desktop-16a: a shelf with nothing in it must not occupy space or explain its own absence
    // (proposal rule) -- each shelf composable now renders nothing at all when its list is empty.
    HomeShelf("Continue with Live TV", live, onBrowse = { onBrowse(CatalogType.LIVE, "__continue") }, onPlay = { onPlay(it, null) })
    HomeShelf("Continue watching movies", movies, onBrowse = { onBrowse(CatalogType.MOVIES, "__continue") }, onPlay = { onPlay(it, null) })
    // Series continuation is tracked per-episode (episode_resume), not on the series row's own
    // resume_ms -- "__continue" now resolves that correctly for SERIES too (see items() below).
    EpisodeHomeShelf(episodes, onBrowse = { onBrowse(CatalogType.SERIES, "__continue") }, onPlay = onPlay)
    HomeShelf("Suggested movies", suggestions[CatalogType.MOVIES].orEmpty(), onBrowse = { onBrowse(CatalogType.MOVIES, "__suggestions") }, onPlay = { onPlay(it, null) })
    HomeShelf("Suggested series", suggestions[CatalogType.SERIES].orEmpty(), onBrowse = { onBrowse(CatalogType.SERIES, "__suggestions") }, onPlay = { onPlay(it, null) })
    HomeShelf("Suggested live channels", suggestions[CatalogType.LIVE].orEmpty(), onBrowse = { onBrowse(CatalogType.LIVE, "__suggestions") }, onPlay = { onPlay(it, null) })
    // D-Desktop-16a first-run/empty-library state: once the initial load has resolved
    // successfully and there is genuinely nothing to continue, say so with a next action instead
    // of leaving three vanished shelves and no explanation.
    if (loaded && refreshMessage.isBlank() && !hasAnyContinuation) {
        Column(Modifier.fillMaxWidth().padding(top = 30.dp).clip(RoundedCornerShape(14.dp)).background(Elevated).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(22.dp)) {
            Text("Nothing to continue yet", color = Ink, style = MaterialTheme.typography.titleLarge)
            Text("Play something from Movies, Series, or Live TV and it will show up here next time.", color = Dim, modifier = Modifier.padding(top = 6.dp))
        }
    }
    Text("Explore", color = Ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 30.dp, bottom = 14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CatalogType.entries.forEach { type -> Box(Modifier.weight(1f).height(172.dp).clip(RoundedCornerShape(14.dp)).background(Elevated).border(1.dp, Line, RoundedCornerShape(14.dp)).clickable { onBrowse(type, "__all") }) { ArtworkImage(null, Modifier.fillMaxSize(), homeTile(type)); Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE80A1619)))).padding(18.dp), verticalArrangement = Arrangement.Bottom) { Text(type.title, color = Ink, style = MaterialTheme.typography.titleLarge); Text(if (type == CatalogType.LIVE) "Channels and live events" else "Browse your library", color = Dim, modifier = Modifier.padding(top = 3.dp)) } } }
    }
    }
}

// D-Desktop-16a: renders nothing (no title, no placeholder copy) when empty -- an empty shelf must
// not occupy space or explain its own absence.
@Composable private fun HomeShelf(title: String, items: List<DesktopItem>, onBrowse: () -> Unit, onPlay: (DesktopItem) -> Unit) {
    if (items.isEmpty()) return
    Text(title, color = Ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 30.dp, bottom = 14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items.forEach { item -> Box(Modifier.width(190.dp)) { CatalogCard(item) { onPlay(item) } } }; Text("See all", color = Accent, modifier = Modifier.align(Alignment.CenterVertically).clickable(onClick = onBrowse).padding(12.dp)) }
}

@Composable private fun EpisodeHomeShelf(items: List<DesktopSeriesResume>, onBrowse: () -> Unit, onPlay: (DesktopItem, DesktopEpisode) -> Unit) {
    if (items.isEmpty()) return
    Text("Continue watching series", color = Ink, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 30.dp, bottom = 14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items.forEach { resume -> Box(Modifier.width(190.dp).height(184.dp).clip(RoundedCornerShape(12.dp)).background(Elevated).border(1.dp, Line, RoundedCornerShape(12.dp)).clickable { onPlay(resume.series, resume.episode) }) { ArtworkImage(resume.series.artwork, Modifier.fillMaxSize()); Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE0A1619)))).padding(12.dp), verticalArrangement = Arrangement.Bottom) { Text(resume.series.title, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("S${resume.episode.season.padStart(2, '0')} E${(resume.episode.episodeNumber ?: "?").padStart(2, '0')}", color = Accent, modifier = Modifier.padding(top = 4.dp)) } } }; Text("See all", color = Accent, modifier = Modifier.align(Alignment.CenterVertically).clickable(onClick = onBrowse).padding(12.dp)) }
}

// D-Desktop-16b: `saved` is hoisted at DesktopShell scope (one per CatalogType) instead of
// `remember`ed here, so filter/category-search/search-text/scroll position/last-opened-card all
// survive leaving this screen (Detail, Episodes, Player) and coming back -- the proposal's
// "preserve tab, scroll position, and focused/selected card" requirement.
@Composable private fun BrowseScreen(repository: DesktopCatalogRepository, type: CatalogType, saved: BrowseSavedState, onDetail: (DesktopItem) -> Unit) {
    val scope = rememberCoroutineScope(); var categories by remember(type) { mutableStateOf(emptyList<com.dev.tvivo.desktop.catalog.DesktopCategory>()) }; var message by remember(type) { mutableStateOf("Refresh to download your ${type.title.lowercase()} library.") }
    fun load() { scope.launch { runCatching { withContext(Dispatchers.IO) { repository.ensureLoaded(type); repository.categories(type) to repository.items(type, saved.filter, saved.query) } }.onSuccess { (cats, rows) -> categories = cats; saved.items = rows; message = if (rows.isNotEmpty()) "${rows.size} items" else "No items available for this selection." }.onFailure { message = it.message ?: "Library unavailable offline." } } }
    androidx.compose.runtime.LaunchedEffect(type, saved.filter, saved.query) { load() }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(220.dp).fillMaxHeight().background(Surface).padding(12.dp)) {
            OutlinedTextField(saved.categoryFilter, { saved.categoryFilter = it }, label = { Text("Filter categories") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            listOf("__all" to "All ${type.title}", "__recent" to "Recently added", "__continue" to "Continue watching", "__favourites" to "Favourites").forEach { (id, label) -> RailButton(label, saved.filter == id) { saved.filter = id } }
            HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn { items(categories.filter { it.name.contains(saved.categoryFilter, ignoreCase = true) }, key = { it.id }) { category -> RailButton(category.name, saved.filter == category.id) { saved.filter = category.id } } }
        }
        Column(Modifier.weight(1f).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(type.title, style = MaterialTheme.typography.headlineMedium, color = Ink); Box(Modifier.weight(1f)); OutlinedTextField(saved.query, { saved.query = it }, label = { Text("Search ${type.title}") }, singleLine = true); Button(onClick = { scope.launch { message = "Refreshing…"; runCatching { withContext(Dispatchers.IO) { repository.refresh(type) } }.onSuccess { load(); message = "Library refreshed." }.onFailure { message = it.message ?: "Refresh failed." } } }, modifier = Modifier.padding(start = 10.dp)) { Text("Refresh") } }
            Text(message, color = Dim, modifier = Modifier.padding(vertical = 10.dp))
            LazyVerticalGrid(GridCells.Adaptive(if (type == CatalogType.LIVE) 170.dp else 140.dp), state = saved.gridState, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) { items(saved.items, key = { it.id }) { item -> CatalogCard(item, highlighted = item.id == saved.highlightedId) { onDetail(item) } } }
        }
    }
}

@Composable private fun RailButton(label: String, selected: Boolean, onClick: () -> Unit) = Text(label, color = if (selected) Color(0xFF1A0A05) else Ink, maxLines = 2, overflow = TextOverflow.Clip, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).background(if (selected) Accent else Color.Transparent).clickable(onClick = onClick).padding(10.dp))
@Composable private fun CatalogCard(item: DesktopItem, highlighted: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        // D-Desktop-16b: the card last opened from this grid (before Detail/Episodes/Player) keeps
        // an accent border on return, so "preserve ... the focused/selected card" is visible, not
        // just an unannounced scroll offset.
        Modifier.height(if (item.type == CatalogType.LIVE) 112.dp else 200.dp).clip(shape).background(Elevated).border(if (highlighted) 2.dp else 1.dp, if (highlighted) Accent else Line, shape).clickable(onClick = onClick),
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
    val surface = remember { Canvas().apply { background = AwtColor.BLACK; isFocusable = true } }
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
                .onSuccess {
                    if (item.type == CatalogType.LIVE) repository.recordTuned(item)
                    withContext(Dispatchers.Main) { playerReady = true }
                }
                .onFailure { val message = it.message; withContext(Dispatchers.Main) { terminal = true; state = "Player setup failed: $message" } }
        }
        onDispose {
            if (item.type != CatalogType.LIVE && lastKnownPositionMs > 0L) repository.recordResume(item, episode, lastKnownPositionMs)
            // close() enqueues a non-blocking terminal task on the player's own owner thread --
            // safe to call from this synchronous disposal callback.
            player.close()
        }
    }
    // D-Desktop-14 input forwarding (Codex-reviewed 2026-09-15): mpv's --wid child window is
    // created WS_DISABLED on Windows and never receives OS input directly, so every mouse/
    // keyboard event that should reach mpv's own OSC/keybindings is forwarded explicitly through
    // MpvPlayer's client-API commands instead. requestFocusInWindow() on press is required for
    // the AWT KeyListener below to receive anything at all.
    DisposableEffect(surface, player) {
        val mouseListener = object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                surface.requestFocusInWindow()
                // Codex review (2026-09-15): use this event's own coordinates, not whatever move
                // happened to be coalesced last -- a press immediately on Canvas entry, or right
                // after a coalesced move, must not activate an OSC control at a stale position.
                player.sendMouseMove(e.x, e.y)
                mpvMouseButtonName(e.button)?.let { player.sendMouseButton(it, pressed = true) }
            }
            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                player.sendMouseMove(e.x, e.y)
                mpvMouseButtonName(e.button)?.let { player.sendMouseButton(it, pressed = false) }
            }
            // Codex research (2026-09-15, mpv issue #9910): forwarded mouse-move alone never
            // triggers OSC's own hover detector, so visibility is forced explicitly instead.
            override fun mouseEntered(e: java.awt.event.MouseEvent) = player.setOscVisibility("always")
            override fun mouseExited(e: java.awt.event.MouseEvent) = player.setOscVisibility("auto")
        }
        val motionListener = object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) = player.sendMouseMove(e.x, e.y)
            override fun mouseDragged(e: java.awt.event.MouseEvent) = player.sendMouseMove(e.x, e.y)
        }
        val wheelListener = java.awt.event.MouseWheelListener { e -> player.sendWheel(up = e.wheelRotation < 0) }
        val keyListener = object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) { mpvKeyName(e)?.let { player.sendKey(it, pressed = true) } }
            override fun keyReleased(e: java.awt.event.KeyEvent) { mpvKeyName(e)?.let { player.sendKey(it, pressed = false) } }
        }
        val focusListener = object : java.awt.event.FocusAdapter() {
            // A key or button that never gets its matching keyup (focus stolen mid-press) would
            // otherwise stay logically down inside mpv forever (Codex review, 2026-09-15).
            override fun focusLost(e: java.awt.event.FocusEvent) { player.releaseAllHeldKeys(); player.setOscVisibility("auto") }
        }
        val resizeListener = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) { player.updateCanvasSize(surface.width, surface.height) }
        }
        surface.addMouseListener(mouseListener)
        surface.addMouseMotionListener(motionListener)
        surface.addMouseWheelListener(wheelListener)
        surface.addKeyListener(keyListener)
        surface.addFocusListener(focusListener)
        surface.addComponentListener(resizeListener)
        player.updateCanvasSize(surface.width, surface.height)
        onDispose {
            player.releaseAllHeldKeys()
            surface.removeMouseListener(mouseListener)
            surface.removeMouseMotionListener(motionListener)
            surface.removeMouseWheelListener(wheelListener)
            surface.removeKeyListener(keyListener)
            surface.removeFocusListener(focusListener)
            surface.removeComponentListener(resizeListener)
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
    androidx.compose.runtime.LaunchedEffect(item.id, episode?.id, playerReady) { while (playerReady) { delay(5_000); if (item.type != CatalogType.LIVE && lastKnownPositionMs > 0L) withContext(Dispatchers.IO) { repository.recordResume(item, episode, lastKnownPositionMs) } } }
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

/** D-Desktop-14 input forwarding: maps AWT mouse buttons to mpv's synthetic MBTN_* key names,
 * the mechanism mpv's own OSC/keybindings recognise (mpv's `mouse` command only ever moves the
 * cursor; clicks are ordinary keydown/keyup on these names -- mpv issues #2596/#9910). */
private fun mpvMouseButtonName(awtButton: Int): String? = when (awtButton) {
    java.awt.event.MouseEvent.BUTTON1 -> "MBTN_LEFT"
    java.awt.event.MouseEvent.BUTTON2 -> "MBTN_MID"
    java.awt.event.MouseEvent.BUTTON3 -> "MBTN_RIGHT"
    else -> null
}

/** D-Desktop-14 input forwarding: maps the AWT keys mpv's default OSC/keybindings actually use
 * (play/pause, seek, fullscreen, mute, volume) to mpv's own key-name syntax
 * (https://github.com/mpv-player/mpv/blob/master/DOCS/man/input.rst#key-names). Printable keys
 * fall back to the typed character, since mpv expects layout-translated text names there, not
 * physical keycodes. */
private fun mpvKeyName(e: java.awt.event.KeyEvent): String? = when (e.keyCode) {
    java.awt.event.KeyEvent.VK_SPACE -> "SPACE"
    java.awt.event.KeyEvent.VK_LEFT -> "LEFT"
    java.awt.event.KeyEvent.VK_RIGHT -> "RIGHT"
    java.awt.event.KeyEvent.VK_UP -> "UP"
    java.awt.event.KeyEvent.VK_DOWN -> "DOWN"
    java.awt.event.KeyEvent.VK_ESCAPE -> "ESC"
    java.awt.event.KeyEvent.VK_ENTER -> "ENTER"
    else -> e.keyChar.takeIf { it.code != java.awt.event.KeyEvent.CHAR_UNDEFINED.code && !Character.isISOControl(it) }?.toString()
}
