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
        verticalArrangement = Arrangement.spacedBy(LandingSectionSpacing),
        content = content
    )
}

@Composable
fun landingContentPadding(): PaddingValues {
    val horizontal = when {
        LocalIsTelevision.current -> 48.dp
        LocalWindowSizeClass.current == WindowSizeClass.Compact -> 16.dp
        else -> 24.dp
    }
    return PaddingValues(start = horizontal, end = horizontal,
        top = if (LocalIsTelevision.current) 27.dp else 20.dp, bottom = 40.dp)
}
internal val LandingSectionSpacing = 16.dp

/** Stable, non-focusable placeholder used until a shelf's first snapshot is loaded. */
@Composable
fun LandingLoadingSkeleton(modifier: Modifier = Modifier, channel: Boolean = false) {
    Row(modifier.fillMaxWidth().testTag("landing-shelf-loading"),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) {
            Box(Modifier.width(110.dp).height(if (channel) 62.dp else 165.dp)
                .background(AppColors.Elevated, RoundedCornerShape(8.dp)))
        }
        Spacer(Modifier.weight(1f))
    }
}
