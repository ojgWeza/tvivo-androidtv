package com.dev.Tvivo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.adaptive.LocalWindowSizeClass
import com.dev.Tvivo.ui.adaptive.WindowSizeClass
import com.dev.Tvivo.ui.adaptive.televisionDensity

/** Shared scrolling surface for My Tvivo and the three catalog landings. */
@Composable
fun LandingScaffold(
    state: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize().background(AppColors.Bg),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(if (LocalIsTelevision.current) televisionDensity().sectionGap.dp else LandingSectionSpacing),
        content = content
    )
}

@Composable
fun landingContentPadding(): PaddingValues {
    val density = if (LocalIsTelevision.current) televisionDensity() else null
    val horizontal = when {
        LocalIsTelevision.current -> density!!.sideGutter.dp
        LocalWindowSizeClass.current == WindowSizeClass.Compact -> 16.dp
        else -> 24.dp
    }
    return PaddingValues(start = horizontal, end = horizontal,
        top = if (LocalIsTelevision.current) (density!!.headerHeight / 3).dp else 20.dp,
        bottom = if (LocalIsTelevision.current) (density!!.headerHeight / 3).dp else 40.dp)
}
internal val LandingSectionSpacing = 16.dp

/** Stable, non-focusable placeholder used until a shelf's first snapshot is loaded. */
@Composable
fun LandingLoadingSkeleton(modifier: Modifier = Modifier, channel: Boolean = false) {
    val density = if (LocalIsTelevision.current) televisionDensity() else null
    Row(modifier.fillMaxWidth().testTag("landing-shelf-loading"),
        horizontalArrangement = Arrangement.spacedBy(if (LocalIsTelevision.current) 8.dp else 10.dp)) {
        repeat(if (LocalIsTelevision.current) 4 else 3) {
            Box(Modifier.width(if (LocalIsTelevision.current) density!!.posterWidth.dp else 110.dp)
                .height(if (LocalIsTelevision.current) { if (channel) density!!.channelHeight.dp else density!!.posterHeight.dp }
                    else if (channel) 62.dp else 165.dp)
                .background(AppColors.Elevated, RoundedCornerShape(8.dp)))
        }
        Spacer(Modifier.weight(1f))
    }
}
