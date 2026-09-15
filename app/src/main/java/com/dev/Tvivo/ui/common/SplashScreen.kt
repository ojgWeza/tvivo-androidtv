package com.dev.Tvivo.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.dev.Tvivo.R
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

/**
 * D-10. Shown while the credentials are decrypted and the cached catalog is opened.
 *
 * **A real progress bar, not a spinner.** The wait has a known set of steps — Tink
 * decrypt, then Room open — so a determinate bar can tell the truth about how far along
 * it is. A spinner would only say "something is happening", which on a cold start of an
 * app holding 68k cached rows is the question the user actually wants answered.
 *
 * [progress] is therefore driven by the caller's real steps and must never be animated
 * from 0 to 1 on a timer to look busy; that is a spinner wearing a bar's clothes.
 */
@Composable
fun SplashScreen(progress: Float, caption: String) {
    // Eased rather than snapping between steps: two discrete jumps read as a stuck bar
    // twice, where a short tween reads as continuous movement.
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220),
        label = "splash-progress"
    )

    Box(
        modifier = Modifier.fillMaxSize().background(Palette.Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                // The background-less variant. `ic_launcher_mark` carries its own tile
                // surface for the launcher, which on a screen that already has one shows
                // up as a lighter box behind the mark.
                painter = painterResource(R.drawable.ic_mark),
                contentDescription = null,
                modifier = Modifier.size(160.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(text = "Tvivo", color = Palette.Ink, style = TvType.display)
            Spacer(Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .width(PROGRESS_WIDTH)
                    .height(PROGRESS_HEIGHT)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Palette.Line)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Palette.Accent)
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(text = caption, color = Palette.Dim, style = TvType.label)
        }
    }
}

private val PROGRESS_WIDTH = 320.dp
private val PROGRESS_HEIGHT = 6.dp
