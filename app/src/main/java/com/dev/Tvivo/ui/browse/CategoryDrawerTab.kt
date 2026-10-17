package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.dev.Tvivo.home.FocusLinkPolicy
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType

@Composable
fun CategoryDrawerTab(
    focusRequester: FocusRequester,
    contentFocusRequester: FocusRequester,
    contentAvailable: Boolean = true,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.width(56.dp).fillMaxHeight(0.82f)
            .focusRequester(focusRequester)
            .focusProperties {
                right = FocusLinkPolicy.targetIfRendered(contentFocusRequester, contentAvailable)
                    ?: FocusRequester.Default
            }
            .semantics { contentDescription = "Open categories" }
            .testTag("category-drawer-tab")
            .tvFocusFrame().tvClickable(onClick = onOpen),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Open categories",
                tint = AppColors.Ink)
            Box(Modifier.width(56.dp).height(190.dp), contentAlignment = Alignment.Center) {
                Text("Categories", color = AppColors.Ink, style = TvType.label,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.requiredWidth(150.dp).wrapContentHeight().rotate(-90f))
            }
        }
    }
}
