package com.dev.Tvivo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType

/**
 * One 48 dp icon and label action used by browse headers and global actions.
 *
 * Refresh, Account and Exit are global actions, and this is the one component that draws
 * all of them — on Home (D-7) and in the browse header (D-8). Refresh was previously a
 * bare text link in the header and an action row on the Account screen, which made one
 * action look like two different things.
 *
 * The label is always visible so its action is clear before D-pad focus arrives. The
 * coral frame remains the only focus treatment.
 *
 * **[icon] is an `ImageVector`, not a character (Q-16).** The first cut used Unicode
 * placeholders and `⏻` (U+23FB) had no glyph in the TV font stack — it drew as a
 * missing-character box, which is the one thing a glyph-at-rest control cannot do.
 * `material-icons-core` is already a dependency and its vectors are guaranteed to
 * render at any density, so there is no reason to gamble on font coverage. These are
 * still not the final marks: T-D1 draws a real set against the brand mark's language.
 */
@Composable
fun IconPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .heightIn(min = PILL_HEIGHT)
            // `widthIn` rather than a fixed width: label lengths differ.
            .widthIn(min = PILL_HEIGHT)
            .background(
                color = AppColors.Elevated,
                shape = PILL_SHAPE
            )
            .tvFocusFrame(shape = PILL_SHAPE)
            // Not `clickable`: its default indication paints a rectangle that ignores
            // the stadium shape, which is Q-15.
            .tvClickable(enabled = enabled) { onClick() }
            // 12 dp centres the icon while leaving room for the label.
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val ink = if (enabled) AppColors.Ink else AppColors.Dim
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = ink,
            modifier = Modifier.size(ICON_SIZE)
        )
        Text(text = label, color = ink, style = TvType.label, maxLines = 1,
            modifier = Modifier.padding(start = 10.dp))
    }
}

/** Compact, consistently shaped 48 dp icon action for browse headers and popup triggers. */
@Composable
fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .background(AppColors.Elevated, PILL_SHAPE)
            .tvFocusFrame(shape = PILL_SHAPE)
            .semantics { this.contentDescription = contentDescription }
            .tvClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) AppColors.Ink else AppColors.Dim,
            modifier = Modifier.size(ICON_SIZE)
        )
    }
}

/** 48 dp minimum target; intrinsic width keeps the complete action label in its frame. */
private val PILL_HEIGHT = 48.dp

private val ICON_SIZE = 24.dp

private val PILL_SHAPE = RoundedCornerShape(percent = 50)

/** Material-style sort marks as a local vector, available without the extended icon pack. */
@Composable
internal fun sortIconVector(): ImageVector {
    val ink = AppColors.Ink
    return remember(ink) {
        ImageVector.Builder(
            name = "Sort",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(ink)) {
                moveTo(3f, 18f); horizontalLineTo(9f); verticalLineTo(16f); horizontalLineTo(3f); close()
                moveTo(3f, 13f); horizontalLineTo(15f); verticalLineTo(11f); horizontalLineTo(3f); close()
                moveTo(3f, 6f); verticalLineTo(8f); horizontalLineTo(21f); verticalLineTo(6f); close()
            }
        }.build()
    }
}
