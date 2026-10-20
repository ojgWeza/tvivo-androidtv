package com.dev.Tvivo.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Menu
import com.dev.Tvivo.ui.common.LiveTvIcon
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.dev.Tvivo.R
import com.dev.Tvivo.AppIdentity
import com.dev.Tvivo.home.ShellTab
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.LocalWindowSizeClass
import com.dev.Tvivo.ui.adaptive.WindowSizeClass
import com.dev.Tvivo.ui.adaptive.televisionDensity
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType

data class ShellFocus(val topBar: FocusRequester, val firstContent: FocusRequester)
val LocalShellFocus = staticCompositionLocalOf<ShellFocus?> { null }

@Composable
fun AppShell(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    sortEnabled: Boolean = false,
    openedCategory: Boolean = false,
    refreshEnabled: Boolean = false,
    sortChoice: com.dev.Tvivo.home.CatalogSort = com.dev.Tvivo.home.CatalogSort.MostVisited,
    onSortSelected: (com.dev.Tvivo.home.CatalogSort) -> Unit = {},
    onRefresh: () -> Unit = {},
    onTheme: () -> Unit = {},
    showPhoneTopBar: Boolean = true,
    content: @Composable () -> Unit
) {
    val television = LocalIsTelevision.current
    val tvDensity = if (television) televisionDensity() else null
    val compact = LocalWindowSizeClass.current == WindowSizeClass.Compact
    val shellFocus = remember { ShellFocus(FocusRequester(), FocusRequester()) }
    val safeContent = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
    if (television) {
        Column(Modifier.fillMaxSize().background(AppColors.Bg)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(tvDensity!!.shellHeight.dp)
                    .background(AppColors.Surface).padding(start = tvDensity.sideGutter.dp, end = tvDensity.sideGutter.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(AppIdentity.name().uppercase(), color = AppColors.Ink, style = TvType.title,
                    modifier = Modifier.width(108.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                listOf(ShellTab.MyTvivo, ShellTab.Movies, ShellTab.Series, ShellTab.Live).forEach { tab ->
                    TabButton(tab, selected, onSelect,
                        underlineSelected = true,
                        modifier = if (tab == selected) Modifier.focusRequester(shellFocus.topBar)
                            else Modifier)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                ShellActionButton(stringResource(com.dev.Tvivo.R.string.nav_search), Icons.Default.Search,
                    enabled = true, onClick = { onSelect(ShellTab.Search) })
                ShellSortButton(sortChoice, sortEnabled, openedCategory, onSortSelected)
                ShellActionButton(stringResource(com.dev.Tvivo.R.string.common_refresh), Icons.Default.Refresh,
                    enabled = refreshEnabled, onClick = onRefresh)
                ShellActionButton(stringResource(com.dev.Tvivo.R.string.theme), "◐",
                    enabled = true, onClick = onTheme)
                ShellActionButton(stringResource(com.dev.Tvivo.R.string.nav_settings), Icons.Default.Settings,
                    enabled = true, onClick = { onSelect(ShellTab.Settings) })
            }
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalShellFocus provides shellFocus) { content() }
            }
        }
    } else if (compact) {
        Column(Modifier.fillMaxSize().background(AppColors.Bg).then(safeContent)) {
            if (showPhoneTopBar) Row(Modifier.fillMaxWidth().height(56.dp).background(AppColors.Surface)
                .padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(when (selected) {
                    ShellTab.MyTvivo -> R.string.nav_for_you
                    ShellTab.Movies -> R.string.nav_movies
                    ShellTab.Series -> R.string.nav_series
                    ShellTab.Live -> R.string.nav_live_tv
                    ShellTab.Search -> R.string.nav_search
                    ShellTab.Settings -> R.string.nav_settings
                }), color = AppColors.Ink, style = TvType.title, modifier = Modifier.weight(1f))
                ShellActionButton(stringResource(R.string.nav_search), Icons.Default.Search,
                    enabled = true, onClick = { onSelect(ShellTab.Search) })
            }
            Box(Modifier.weight(1f)) { content() }
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).background(AppColors.Surface),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(ShellTab.MyTvivo, ShellTab.Movies, ShellTab.Series, ShellTab.Live, ShellTab.Settings).forEach { tab ->
                    TabButton(tab, selected, onSelect, compact = true, modifier = Modifier.weight(1f))
                }
            }
        }
    } else {
        Row(Modifier.fillMaxSize().background(AppColors.Bg).then(safeContent)) {
            Column(
                modifier = Modifier.width(176.dp).fillMaxSize()
                    .background(AppColors.Surface).verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(AppIdentity.name().uppercase(), color = AppColors.Ink, style = TvType.title)
                ShellTab.entries.forEach { tab -> TabButton(tab, selected, onSelect) }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun TabButton(
    tab: ShellTab,
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    compact: Boolean = false,
    underlineSelected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val title = when (tab) {
        ShellTab.MyTvivo -> stringResource(R.string.nav_for_you)
        ShellTab.Movies -> stringResource(R.string.nav_movies)
        ShellTab.Series -> stringResource(R.string.nav_series)
        ShellTab.Live -> stringResource(if (compact) R.string.nav_live else R.string.nav_live_tv)
        ShellTab.Search -> stringResource(R.string.nav_search)
        ShellTab.Settings -> stringResource(R.string.nav_settings)
    }
    val ink = if (tab == selected) AppColors.AccentText else AppColors.Ink
    val iconsOnly = compact && LocalConfiguration.current.fontScale >= 1.6f
    val buttonModifier = modifier.testTag("shell-${tab.name}").semantics { contentDescription = title }
        .heightIn(min = 56.dp)
        .tvFocusFrame().tvClickable { onSelect(tab) }
        .then(if (compact && tab == selected && LocalIsTelevision.current) Modifier.border(2.dp, AppColors.AccentText, RoundedCornerShape(12.dp)) else Modifier)
        .padding(horizontal = if (compact) 4.dp else 16.dp, vertical = 12.dp)
    if (compact) {
        Column(buttonModifier, horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            when (tab) {
                ShellTab.MyTvivo -> Icon(Icons.Default.Home, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                ShellTab.Movies -> Icon(Icons.Default.PlayArrow, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                ShellTab.Series -> Icon(Icons.Default.Menu, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                ShellTab.Live -> Icon(LiveTvIcon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                ShellTab.Settings -> Icon(Icons.Default.Settings, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                ShellTab.Search -> Icon(Icons.Default.Search, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            }
            if (!iconsOnly) Text(title, color = ink, style = TvType.label,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else if (underlineSelected) {
        // Intrinsic width: the underline's fillMaxWidth() would otherwise take the whole
        // row and squeeze every other tab out of the top bar.
        Column(
            modifier = buttonModifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(title, color = ink, style = TvType.label)
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(4.dp)
                .background(if (tab == selected) AppColors.Accent else AppColors.Surface))
        }
    } else Text(title, color = ink, style = TvType.label, modifier = buttonModifier,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun ScreenTopBar(title: String, onBack: () -> Unit) {
    if (LocalIsTelevision.current) return
    val back = stringResource(R.string.back)
    Row(Modifier.fillMaxWidth().height(56.dp).background(AppColors.Surface)
        .padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).testTag("screen-back").semantics { contentDescription = back }
            .tvFocusFrame().tvClickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Text(if (LocalLayoutDirection.current == LayoutDirection.Rtl) "→" else "←",
                color = AppColors.Ink, style = TvType.title)
        }
        Text(title, color = AppColors.Ink, style = TvType.title, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ShellActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) = ShellActionButton(label, icon as Any, enabled, onClick)

@Composable
private fun ShellActionButton(label: String, icon: Any, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.size(52.dp).onFocusChanged { focused = it.isFocused }
        .semantics { contentDescription = label }
        .testTag("shell-action-${label}").tvFocusFrame().tvClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (icon is androidx.compose.ui.graphics.vector.ImageVector) Icon(icon, contentDescription = null,
            tint = if (enabled) AppColors.Ink else AppColors.Dim, modifier = Modifier.size(24.dp))
        else Text(icon.toString(), color = if (enabled) AppColors.Ink else AppColors.Dim, style = TvType.title)
        if (focused) Text(label, color = AppColors.Ink, style = TvType.caption,
            modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ShellSortButton(
    selected: com.dev.Tvivo.home.CatalogSort,
    enabled: Boolean,
    openedCategory: Boolean,
    onSelected: (com.dev.Tvivo.home.CatalogSort) -> Unit
) {
    val sortLabel = stringResource(R.string.sort_items_accessibility)
    var focused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(52.dp).onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = sortLabel }
            .testTag("shell-action-sort").tvFocusFrame()
            .tvClickable(enabled = enabled, onClick = { expanded = true }),
            contentAlignment = Alignment.Center) {
            Text("☷", color = if (enabled) AppColors.Ink else AppColors.Dim, style = TvType.title)
            if (focused) Text(sortLabel, color = AppColors.Ink,
                style = TvType.caption, modifier = Modifier.align(Alignment.BottomCenter))
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (if (openedCategory) emptyList() else listOf(com.dev.Tvivo.home.CatalogSort.MostVisited))
                .plus(listOf(com.dev.Tvivo.home.CatalogSort.AlphabeticalAsc,
                com.dev.Tvivo.home.CatalogSort.AlphabeticalDesc,
                com.dev.Tvivo.home.CatalogSort.RecentlyAdded)).forEach { sort ->
                val label = when (sort) {
                    com.dev.Tvivo.home.CatalogSort.MostVisited -> stringResource(R.string.sort_most_visited)
                    com.dev.Tvivo.home.CatalogSort.AlphabeticalAsc -> "A–Z"
                    com.dev.Tvivo.home.CatalogSort.AlphabeticalDesc -> "Z–A"
                    com.dev.Tvivo.home.CatalogSort.RecentlyAdded -> stringResource(R.string.sort_recently_added)
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (selected == sort) "✓ $label" else label, color = AppColors.Ink) },
                    onClick = { expanded = false; onSelected(sort) })
            }
        }
    }
}
