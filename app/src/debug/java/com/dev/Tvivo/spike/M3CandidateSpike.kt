package com.dev.Tvivo.spike

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dev.Tvivo.R
import com.dev.Tvivo.ui.adaptive.LocalIsTelevision
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.AppColorScheme
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.AppShapes
import com.dev.Tvivo.ui.theme.DefaultAppColors
import com.dev.Tvivo.ui.theme.TvType
import com.dev.Tvivo.ui.theme.TvivoTheme

/** A disposable, debug-only comparison surface. All data and callbacks are supplied by its host. */
data class CandidatePoster(
    val title: String,
    val quality: String,
    val progress: Float,
    val favourite: Boolean
)

/** Mirrors the TV theme's roles while the two libraries coexist. */
private fun m3Colors(colors: AppColorScheme) = darkColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = colors.elevated,
    onPrimaryContainer = colors.ink,
    secondary = colors.line,
    onSecondary = colors.ink,
    secondaryContainer = colors.elevated,
    onSecondaryContainer = colors.ink,
    tertiary = colors.accent,
    onTertiary = colors.onAccent,
    background = colors.bg,
    onBackground = colors.ink,
    surface = colors.elevated,
    onSurface = colors.ink,
    surfaceVariant = colors.surface,
    onSurfaceVariant = colors.dim,
    outline = colors.line,
    outlineVariant = colors.line,
    scrim = colors.bg,
    error = colors.accentText,
    onError = colors.onAccent,
    errorContainer = colors.elevated,
    onErrorContainer = colors.accentText
)

private val m3Typography = Typography(
    displayLarge = TvType.display,
    displayMedium = TvType.display,
    displaySmall = TvType.display,
    headlineLarge = TvType.headline,
    headlineMedium = TvType.headline,
    headlineSmall = TvType.headline,
    titleLarge = TvType.title,
    titleMedium = TvType.title,
    titleSmall = TvType.title,
    bodyLarge = TvType.body,
    bodyMedium = TvType.body,
    bodySmall = TvType.label,
    labelLarge = TvType.label,
    labelMedium = TvType.label,
    labelSmall = TvType.caption
)

/** M3 ripple off: `TvFocusFrame` must be the only focus/press cue. */
private object NoIndication : Indication {
    private object NoInstance : IndicationInstance {
        override fun ContentDrawScope.drawIndication() = drawContent()
    }

    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance =
        NoInstance
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3CandidateSpike(
    poster: CandidatePoster,
    isTelevision: Boolean,
    onHomeActivate: () -> Unit,
    onPosterActivate: () -> Unit,
    appColors: AppColorScheme = DefaultAppColors,
    onHomeTextLayout: (TextLayoutResult) -> Unit = {},
    onPosterTextLayout: (TextLayoutResult) -> Unit = {},
    onThemeObserved: (ColorScheme) -> Unit = {},
    onMinimumTargetEnforcementObserved: (Boolean) -> Unit = {}
) {
    TvivoTheme(appColors = appColors) {
        CompositionLocalProvider(
            LocalIsTelevision provides isTelevision,
            LocalIndication provides NoIndication
        ) {
            val television = LocalIsTelevision.current
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentEnforcement provides !television
            ) {
              MaterialTheme(colorScheme = m3Colors(appColors), typography = m3Typography) {
                val scheme = MaterialTheme.colorScheme
                val enforceTouchTargets = LocalMinimumInteractiveComponentEnforcement.current
                SideEffect {
                    onThemeObserved(scheme)
                    onMinimumTargetEnforcementObserved(enforceTouchTargets)
                }
                var selected by remember { mutableStateOf<String?>(null) }
                var restoreTarget by remember { mutableStateOf<String?>(null) }
                val homeFocus = remember { FocusRequester() }
                val posterFocus = remember { FocusRequester() }

                BackHandler(enabled = selected != null) { selected = null }
                LaunchedEffect(selected, restoreTarget) {
                    if (selected == null) when (restoreTarget) {
                        "home" -> homeFocus.requestFocus()
                        "poster" -> posterFocus.requestFocus()
                    }
                }

                if (selected != null) {
                    Box(Modifier.fillMaxSize().background(AppColors.Bg).testTag("candidate-detail")) {
                        Text(
                            text = selected.orEmpty(),
                            color = AppColors.Ink,
                            style = TvType.headline,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.Bg)
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Card(
                            modifier = Modifier
                                .width(160.dp)
                                .height(165.dp)
                                .testTag("candidate-home")
                                .focusRequester(homeFocus)
                                .tvFocusFrame(AppShapes.card)
                                .tvClickable {
                                    restoreTarget = "home"
                                    selected = "Movies"
                                    onHomeActivate()
                                },
                            shape = AppShapes.card,
                            colors = CardDefaults.cardColors(containerColor = AppColors.Elevated)
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Image(
                                    painter = painterResource(R.drawable.tile_movies),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    Modifier.fillMaxSize().background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            1f to AppColors.Bg.copy(alpha = 0.92f)
                                        )
                                    )
                                )
                                Text(
                                    text = "Movies",
                                    color = AppColors.Ink,
                                    style = TvType.headline,
                                    onTextLayout = onHomeTextLayout,
                                    modifier = Modifier.align(Alignment.BottomStart)
                                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                                )
                            }
                        }

                        Card(
                            modifier = Modifier
                                .width(110.dp)
                                .height(165.dp)
                                .testTag("candidate-poster")
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "${poster.title}, ${poster.quality}" +
                                        if (poster.favourite) ", favourite" else ""
                                }
                                .focusRequester(posterFocus)
                                .tvFocusFrame(AppShapes.card)
                                .tvClickable {
                                    restoreTarget = "poster"
                                    selected = poster.title
                                    onPosterActivate()
                                },
                            shape = AppShapes.card,
                            colors = CardDefaults.cardColors(containerColor = AppColors.Elevated)
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Image(
                                    painter = painterResource(R.drawable.tile_series),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text(
                                    text = poster.quality,
                                    color = AppColors.Ink,
                                    style = TvType.caption,
                                    modifier = Modifier.align(Alignment.TopStart)
                                        .background(AppColors.Bg, AppShapes.pill)
                                        .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)
                                )
                                if (poster.favourite) {
                                    Text(
                                        text = "★",
                                        color = AppColors.Favourite,
                                        style = TvType.title,
                                        modifier = Modifier.align(Alignment.TopEnd)
                                            .padding(end = 4.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier.align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .background(AppColors.Bg.copy(alpha = 0.92f))
                                        .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = poster.title,
                                        color = AppColors.Ink,
                                        style = TvType.label,
                                        maxLines = 2,
                                        softWrap = true,
                                        overflow = TextOverflow.Ellipsis,
                                        onTextLayout = onPosterTextLayout
                                    )
                                    LinearProgressIndicator(
                                        progress = poster.progress.coerceIn(0f, 1f),
                                        modifier = Modifier.fillMaxWidth().height(4.dp)
                                            .semantics {
                                                progressBarRangeInfo = ProgressBarRangeInfo(
                                                    poster.progress.coerceIn(0f, 1f), 0f..1f
                                                )
                                            },
                                        color = AppColors.Accent,
                                        trackColor = AppColors.Line
                                    )
                                }
                            }
                        }
                    }
                }
              }
            }
        }
    }
}
