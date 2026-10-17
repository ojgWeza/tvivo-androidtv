package com.dev.Tvivo.ui.shell

import androidx.compose.foundation.background
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import com.dev.Tvivo.AppIdentity
import com.dev.Tvivo.home.ShellTab
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.LocalWindowSizeClass
import com.dev.Tvivo.ui.adaptive.WindowSizeClass
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
    content: @Composable () -> Unit
) {
    val television = LocalIsTelevision.current
    val compact = LocalWindowSizeClass.current == WindowSizeClass.Compact
    val shellFocus = remember { ShellFocus(FocusRequester(), FocusRequester()) }
    val safeContent = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
    if (television) {
        Column(Modifier.fillMaxSize().background(AppColors.Bg)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(80.dp)
                    .background(AppColors.Surface).padding(start = 48.dp, end = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                ShellTab.entries.forEach { tab ->
                    TabButton(tab, selected, onSelect,
                        underlineSelected = true,
                        modifier = if (tab == selected) Modifier.focusRequester(shellFocus.topBar)
                            else Modifier)
                }
            }
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalShellFocus provides shellFocus) { content() }
            }
        }
    } else if (compact) {
        Column(Modifier.fillMaxSize().background(AppColors.Bg).then(safeContent)) {
            Box(Modifier.weight(1f)) { content() }
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).background(AppColors.Surface),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShellTab.entries.filter { it != ShellTab.Settings }.forEach { tab ->
                    TabButton(tab, selected, onSelect, compact = true)
                }
            }
        }
    } else {
        Row(Modifier.fillMaxSize().background(AppColors.Bg).then(safeContent)) {
            Column(
                modifier = Modifier.width(176.dp).fillMaxSize()
                    .background(AppColors.Surface).padding(start = 12.dp, end = 12.dp, top = 20.dp),
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
        ShellTab.MyTvivo -> AppIdentity.personalLibrary()
        ShellTab.Movies -> "Movies"
        ShellTab.Series -> "Series"
        ShellTab.Live -> if (compact) "Live" else "Live TV"
        ShellTab.Search -> "Search"
        ShellTab.Settings -> "Settings"
    }
    val ink = if (tab == selected) AppColors.AccentText else AppColors.Ink
    val buttonModifier = modifier.testTag("shell-${tab.name}").heightIn(min = 56.dp)
        .tvFocusFrame().tvClickable { onSelect(tab) }
        .then(if (compact && tab == selected) Modifier.border(2.dp, AppColors.AccentText, RoundedCornerShape(12.dp)) else Modifier)
        .padding(horizontal = if (compact) 4.dp else 16.dp, vertical = 12.dp)
    if (compact && tab == ShellTab.Search) {
        Row(buttonModifier, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = ink,
                modifier = Modifier.size(18.dp))
            Text(title, color = ink, style = TvType.label)
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
    } else Text(title, color = ink, style = TvType.label, modifier = buttonModifier)
}
