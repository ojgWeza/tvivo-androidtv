package com.dev.Tvivo.ui.player

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import com.dev.Tvivo.R
import com.dev.Tvivo.playback.HeldSeekAccelerator
import com.dev.Tvivo.ui.adaptive.adaptiveSpacing
import com.dev.Tvivo.ui.adaptive.adaptiveType
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

data class TrackChoice(val label: String, val selected: Boolean)

enum class PlayerPopup { Audio, Subtitles, Timer, Warning }

data class PlayerOverlayState(
    val title: String,
    val isLive: Boolean,
    val isPlaying: Boolean = false,
    val seekable: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val audio: List<TrackChoice> = emptyList(),
    val subtitles: List<TrackChoice> = emptyList(),
    val hasNext: Boolean = false,
    val hasResume: Boolean = false,
    val seekFeedback: String? = null,
    val playbackState: String = "playing",
    val nextPrompt: Boolean = false,
    val nextTitle: String? = null,
    val sleepTimer: SleepTimerUiState = SleepTimerUiState(),
)

/** The PlayerView stays behind this transparent Compose layer; this composable owns no player. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerOverlay(
    state: PlayerOverlayState,
    visible: Boolean,
    onToggleOverlay: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: (Long) -> Unit,
    onAudio: (Int) -> Unit,
    onSubtitle: (Int) -> Unit,
    onNext: () -> Unit,
    onStartOver: () -> Unit,
    onInput: () -> Unit = {},
    onSeekKey: (Int, KeyEvent) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
    onCancelNext: () -> Unit = {},
    onTimerCommand: (SleepTimerUiCommand) -> Unit = {},
    onTimerMenuPinned: (Boolean) -> Unit = {},
    dismissMenus: Int = 0,
    onMenuOpenChanged: (PlayerPopup, Boolean) -> Unit = { _, _ -> },
    onEndedWatch: () -> Unit = {},
) {
    val spacing = adaptiveSpacing()
    val type = adaptiveType()
    val controls = PlayerOverlayLogic.controls(state.isLive, state.seekable, state.audio.size, state.subtitles.size, state.hasNext, state.hasResume)
    val backFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val bottomFocus = remember { FocusRequester() }
    val timerFocus = remember { FocusRequester() }
    val endedWatchFocus = remember { FocusRequester() }
    val stoppedBackFocus = remember { FocusRequester() }
    val warningExtendFocus = remember { FocusRequester() }
    var warningDismissed by remember { mutableStateOf(false) }
    var focusRow by remember { mutableStateOf(1) }
    LaunchedEffect(visible, state.playbackState) {
        if (visible) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching {
                (when (state.playbackState) {
                    "retry" -> retryFocus
                    "stopped" -> stoppedBackFocus
                    else -> playFocus
                }).requestFocus()
            }
        }
    }
    LaunchedEffect(state.sleepTimer.isEnded) {
        if (state.sleepTimer.isEnded) runCatching { endedWatchFocus.requestFocus() }
    }
    LaunchedEffect(state.sleepTimer.isWarning) {
        if (state.sleepTimer.isWarning) runCatching { warningExtendFocus.requestFocus() }
        else warningDismissed = false
    }
    LaunchedEffect(state.sleepTimer.isWarning, warningDismissed) {
        onMenuOpenChanged(PlayerPopup.Warning, state.sleepTimer.isWarning && !warningDismissed && !state.sleepTimer.isEnded)
    }
    LaunchedEffect(dismissMenus) {
        if (dismissMenus > 0 && state.sleepTimer.isWarning) {
            warningDismissed = true
            onTimerMenuPinned(false)
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.pointerInput(visible) {
            detectTapGestures(
                onTap = { onToggleOverlay() },
                onDoubleTap = { offset ->
                    val delta = PlayerOverlayLogic.doubleTapSeek(offset.x, size.width.toFloat())
                    if (delta != 0L && controls.scrubber) onSeek(delta) else onInput()
                }
            )
        }
    ) {
        if (state.sleepTimer.isActive && !visible) {
            val remaining = SleepTimerUiPolicy.remainingLabel(state.sleepTimer.remainingMs)
            val label = when {
                state.sleepTimer.mode == SleepTimerUiState.Mode.EndOfCurrentItem -> stringResource(R.string.sleep_timer_ends_with_item)
                remaining == null -> stringResource(R.string.sleep_timer_active)
                remaining.seconds -> stringResource(R.string.sleep_timer_remaining_seconds, remaining.value)
                else -> stringResource(R.string.sleep_timer_remaining_minutes, remaining.value)
            }
            TextButton(
                onClick = onToggleOverlay,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).height(48.dp)
                    .semantics { contentDescription = label }
            ) { Text(label, color = AppColors.MediaText) }
        }
        if (state.sleepTimer.isEnded) {
            Column(
                Modifier.fillMaxSize().background(AppColors.MediaBase.copy(alpha = 0.96f)).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.sleepTimer.endedTitle ?: state.title, color = AppColors.MediaText, style = type.title)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onEndedWatch, modifier = Modifier.height(48.dp).focusRequester(endedWatchFocus)) {
                    Text(stringResource(if (state.isLive) R.string.sleep_timer_watch_channel else R.string.sleep_timer_watch_again))
                }
                TextButton(onClick = onBack, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.back)) }
            }
        } else if (state.playbackState == "stopped") {
            Column(
                Modifier.fillMaxSize().background(AppColors.MediaBase.copy(alpha = 0.96f)).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.title, color = AppColors.MediaText, style = type.title)
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.playback_stopped), color = AppColors.MediaText, style = type.body)
                TextButton(onClick = onBack, modifier = Modifier.height(48.dp).focusRequester(stoppedBackFocus)) {
                    Text(stringResource(R.string.back))
                }
            }
        } else if (visible) {
            Box(
                Modifier.fillMaxSize()
                    .background(AppColors.MediaScrim)
                    .padding(WindowInsets.safeDrawing.asPaddingValues())
                    .padding(horizontal = spacing.outerGutter, vertical = 12.dp)
                    .onPreviewKeyEvent {
                        val native = it.nativeKeyEvent
                        val key = native.keyCode
                        if (native.action == KeyEvent.ACTION_DOWN &&
                            (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
                            val hasOtherBottomControl = controls.scrubber || controls.audio || controls.subtitles || controls.next
                            val next = PlayerOverlayLogic.nextFocusRow(focusRow,
                                key == KeyEvent.KEYCODE_DPAD_DOWN, hasOtherBottomControl)
                            if (next != focusRow) {
                                focusRow = next
                                runCatching { when (next) {
                                    0 -> backFocus
                                    1 -> playFocus
                                    2 -> bottomFocus
                                    else -> timerFocus
                                }.requestFocus() }
                                onInput()
                                true
                            } else false
                        } else false
                    }
            ) {
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OverlayButton(
                        IconKind.Back, stringResource(R.string.back), Modifier.focusRequester(backFocus)
                            .onFocusChanged { if (it.isFocused) focusRow = 0 }.testTag("player_back"),
                        onBack, plain = true
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        state.title, color = AppColors.MediaText, style = type.title,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    if (state.sleepTimer.isActive) {
                        val remaining = SleepTimerUiPolicy.remainingLabel(state.sleepTimer.remainingMs)
                        val timerLabel = when {
                            state.sleepTimer.mode == SleepTimerUiState.Mode.EndOfCurrentItem -> stringResource(R.string.sleep_timer_ends_with_item)
                            remaining == null -> stringResource(R.string.sleep_timer_active)
                            remaining.seconds -> stringResource(R.string.sleep_timer_remaining_seconds, remaining.value)
                            else -> stringResource(R.string.sleep_timer_remaining_minutes, remaining.value)
                        }
                        Text(
                            timerLabel, color = AppColors.MediaText, style = type.caption,
                            modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = timerLabel }
                        )
                    }
                }
                if (state.nextPrompt) Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 72.dp).background(AppColors.MediaBase.copy(alpha = 0.94f), RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Next: ${state.nextTitle ?: "Up next"}", color = AppColors.MediaText, style = type.title)
                    Spacer(Modifier.width(16.dp))
                    OverlayButton(IconKind.Back, stringResource(R.string.player_cancel_next), onClick = onCancelNext)
                }

                // Media direction is physical: rewind | play | forward stays in this visual order in RTL.
                Row(
                    Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.playbackState == "retry") Button(
                        onClick = onRetry, modifier = Modifier.height(48.dp).focusRequester(retryFocus).testTag("retry")
                    ) { Text("Retry") }
                    if (state.playbackState == "reconnecting") Text("Reconnecting…", color = AppColors.MediaText, style = type.title)
                    if (state.playbackState != "retry" && state.playbackState != "reconnecting" && controls.scrubber) OverlayButton(
                        IconKind.Rewind, stringResource(R.string.player_rewind), Modifier.testTag("player_rewind"),
                        onClick = { onSeek(-10_000L) },
                        onKey = { onSeekKey(-1, it) },
                        onHeld = { onSeek(-it) }
                    )
                    if (state.playbackState != "retry" && state.playbackState != "reconnecting") OverlayButton(
                        if (state.isPlaying) IconKind.Pause else IconKind.Play,
                        stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                        Modifier.size(72.dp).focusRequester(playFocus).onFocusChanged { if (it.isFocused) focusRow = 1 }.testTag("player_play_pause"),
                        onPlayPause
                    )
                    if (state.playbackState != "retry" && state.playbackState != "reconnecting" && controls.scrubber) OverlayButton(
                        IconKind.Forward, stringResource(R.string.player_forward), Modifier.testTag("player_forward"),
                        onClick = { onSeek(10_000L) },
                        onKey = { onSeekKey(1, it) },
                        onHeld = { onSeek(it) }
                    )
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    val spokenSeekLabel = if (controls.scrubber) stringResource(
                        R.string.player_seek_position,
                        spokenDuration(state.positionMs), spokenDuration(state.durationMs)
                    ) else ""
                    if (controls.scrubber) {
                        var dragValue by remember { mutableFloatStateOf(-1f) }
                        val sliderInteractionSource = remember { MutableInteractionSource() }
                        val duration = state.durationMs.coerceAtLeast(1L)
                        val seekLabel = stringResource(R.string.player_seek)
                        Slider(
                            value = if (dragValue >= 0f) dragValue else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                            onValueChange = { dragValue = it; onInput() },
                            onValueChangeFinished = {
                                if (dragValue >= 0f) onScrub((dragValue * duration).toLong())
                                dragValue = -1f
                            },
                            interactionSource = sliderInteractionSource,
                            modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(bottomFocus)
                                .onFocusChanged { if (it.isFocused) focusRow = 2 }
                                .tvFocusFrame(RoundedCornerShape(8.dp))
                                .semantics { contentDescription = seekLabel }.testTag("player_seek"),
                            colors = SliderDefaults.colors(
                                thumbColor = AppColors.MediaAccent,
                                activeTrackColor = AppColors.MediaAccent,
                                inactiveTrackColor = AppColors.MediaMuted
                            ),
                            thumb = {
                                SliderDefaults.Thumb(
                                    interactionSource = sliderInteractionSource,
                                    colors = SliderDefaults.colors(thumbColor = AppColors.MediaAccent),
                                    thumbSize = DpSize(24.dp, 24.dp)
                                )
                            }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (controls.scrubber) {
                            Text(
                                "${formatPlayerTime(state.positionMs)} / ${formatPlayerTime(state.durationMs)}",
                                color = AppColors.MediaText,
                                style = type.caption,
                                modifier = Modifier.semantics { contentDescription = spokenSeekLabel }
                            )
                        } else if (state.isLive) Text("LIVE", color = AppColors.MediaText, style = type.label)
                        else Text("${formatPlayerTime(state.positionMs)} / --:--", color = AppColors.MediaText, style = type.caption)
                        Spacer(Modifier.weight(1f))
                        if (controls.startOver) OverlayButton(IconKind.Rewind, stringResource(R.string.player_start_over), Modifier.testTag("player_start_over"), onStartOver)
                        if (controls.audio) TrackMenu(
                            stringResource(R.string.player_audio), IconKind.Audio, state.audio, onAudio,
                            (if (!controls.scrubber) Modifier.focusRequester(bottomFocus).onFocusChanged { if (it.isFocused) focusRow = 2 } else Modifier)
                                .testTag("player_audio"), PlayerPopup.Audio, dismissMenus, onMenuOpenChanged
                        )
                        if (controls.subtitles) TrackMenu(
                            stringResource(R.string.player_subtitles), IconKind.Subtitles, state.subtitles, onSubtitle,
                            (if (!controls.scrubber && !controls.audio)
                                Modifier.focusRequester(bottomFocus).onFocusChanged { if (it.isFocused) focusRow = 2 } else Modifier)
                                .testTag("player_subtitles"), PlayerPopup.Subtitles, dismissMenus, onMenuOpenChanged
                        )
                        if (controls.next) OverlayButton(
                            IconKind.Next, stringResource(R.string.player_next),
                            (if (!controls.scrubber && !controls.audio && !controls.subtitles)
                                Modifier.focusRequester(bottomFocus).onFocusChanged { if (it.isFocused) focusRow = 2 } else Modifier)
                                .testTag("player_next"), onNext
                        )
                        SleepTimerMenu(
                            state.isLive, state.sleepTimer, onTimerCommand, onTimerMenuPinned,
                            Modifier.focusRequester(timerFocus).onFocusChanged { if (it.isFocused) focusRow = 3 }
                                .testTag("player_timer"), dismissMenus, onMenuOpenChanged
                        )
                    }
                }
            }
        }
        if (state.sleepTimer.isWarning && !state.sleepTimer.isEnded && !warningDismissed) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { warningDismissed = true; onTimerMenuPinned(false) },
                title = { Text(stringResource(R.string.sleep_timer_warning_title)) },
                text = { Text(stringResource(R.string.sleep_timer_warning_message)) },
                confirmButton = {
                    Button(
                        onClick = { onTimerCommand(SleepTimerUiPolicy.extend()); onTimerMenuPinned(false) },
                        modifier = Modifier.height(48.dp).focusRequester(warningExtendFocus),
                    ) {
                        Text(stringResource(R.string.sleep_timer_extend))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onTimerCommand(SleepTimerUiPolicy.cancel()); onTimerMenuPinned(false) }, modifier = Modifier.height(48.dp)) {
                        Text(stringResource(R.string.sleep_timer_turn_off))
                    }
                },
            )
        }
        state.seekFeedback?.let { label ->
            val ripple = remember(label) { Animatable(0f) }
            val rippleColor = AppColors.MediaAccent
            LaunchedEffect(label) { ripple.animateTo(1f, tween(550)) }
            Box(Modifier.align(Alignment.Center).padding(top = 112.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(116.dp)) {
                    drawCircle(
                        rippleColor.copy(alpha = (1f - ripple.value) * 0.5f),
                        radius = size.minDimension * (0.2f + 0.3f * ripple.value)
                    )
                }
                Text(
                    label, color = AppColors.MediaText, style = type.title,
                    modifier = Modifier.background(AppColors.MediaBase.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private enum class IconKind { Back, Play, Pause, Rewind, Forward, Audio, Subtitles, Next, Timer }

@Composable
private fun OverlayButton(
    kind: IconKind,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onKey: ((KeyEvent) -> Unit)? = null,
    onHeld: ((Long) -> Unit)? = null,
    plain: Boolean = false,
) {
    val ink = AppColors.MediaText
    Box(
        modifier.size(56.dp).tvFocusFrame(CircleShape)
            .background(AppColors.MediaBase.copy(alpha = if (plain) 0.72f else 0.72f), CircleShape)
            .semantics { contentDescription = label; role = Role.Button }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (onKey != null && native.keyCode in arrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                    onKey(native)
                    true
                } else false
            }
            .tvClickable(onClick = onClick)
            .then(if (onHeld != null) Modifier.pointerInput(onHeld, onClick) {
                detectTapGestures(onPress = {
                    coroutineScope {
                        val start = System.currentTimeMillis()
                        val job = launch {
                            delay(400)
                            while (true) {
                                onHeld(HeldSeekAccelerator.stepMilliseconds(System.currentTimeMillis() - start))
                                delay(300)
                            }
                        }
                        val released = tryAwaitRelease()
                        job.cancel()
                        if (released && System.currentTimeMillis() - start < 400) onClick()
                    }
                })
            } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(28.dp)) {
            val w = size.width; val h = size.height
            fun triangle(x1: Float, x2: Float) {
                val p = Path().apply { moveTo(x1 * w, .18f * h); lineTo(x2 * w, .5f * h); lineTo(x1 * w, .82f * h); close() }
                drawPath(p, ink)
            }
            when (kind) {
                IconKind.Back -> {
                    drawLine(ink, Offset(.72f*w, .5f*h), Offset(.26f*w, .5f*h), strokeWidth = 3.dp.toPx())
                    drawLine(ink, Offset(.26f*w, .5f*h), Offset(.48f*w, .27f*h), strokeWidth = 3.dp.toPx())
                    drawLine(ink, Offset(.26f*w, .5f*h), Offset(.48f*w, .73f*h), strokeWidth = 3.dp.toPx())
                }
                IconKind.Play -> triangle(.30f, .76f)
                IconKind.Pause -> { drawRect(ink, Offset(.28f*w, .2f*h), androidx.compose.ui.geometry.Size(.16f*w, .6f*h)); drawRect(ink, Offset(.56f*w, .2f*h), androidx.compose.ui.geometry.Size(.16f*w, .6f*h)) }
                IconKind.Rewind -> { triangle(.50f, .12f); triangle(.87f, .49f) }
                IconKind.Forward, IconKind.Next -> { triangle(.12f, .50f); triangle(.49f, .87f) }
                IconKind.Timer -> {
                    drawCircle(ink, radius = .35f * w, style = Stroke(2.dp.toPx()))
                    drawLine(ink, Offset(.5f*w, .5f*h), Offset(.5f*w, .28f*h), 2.dp.toPx())
                    drawLine(ink, Offset(.5f*w, .5f*h), Offset(.67f*w, .58f*h), 2.dp.toPx())
                    drawLine(ink, Offset(.4f*w, .08f*h), Offset(.6f*w, .08f*h), 2.dp.toPx())
                }
                IconKind.Audio -> {
                    drawLine(ink, Offset(.15f*w, .35f*h), Offset(.38f*w, .35f*h), 3.dp.toPx())
                    drawLine(ink, Offset(.38f*w, .35f*h), Offset(.60f*w, .18f*h), 3.dp.toPx())
                    drawLine(ink, Offset(.60f*w, .18f*h), Offset(.60f*w, .82f*h), 3.dp.toPx())
                    drawLine(ink, Offset(.60f*w, .82f*h), Offset(.38f*w, .65f*h), 3.dp.toPx())
                    drawLine(ink, Offset(.38f*w, .65f*h), Offset(.15f*w, .65f*h), 3.dp.toPx())
                    drawArc(ink, -60f, 120f, false, topLeft = Offset(.48f*w, .20f*h), size = androidx.compose.ui.geometry.Size(.45f*w, .60f*h), style = Stroke(2.dp.toPx()))
                }
                IconKind.Subtitles -> {
                    drawRoundRect(ink, topLeft = Offset(.1f*w, .2f*h), size = androidx.compose.ui.geometry.Size(.8f*w, .6f*h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
                    drawLine(ink, Offset(.22f*w, .55f*h), Offset(.48f*w, .55f*h), 2.dp.toPx())
                    drawLine(ink, Offset(.52f*w, .55f*h), Offset(.78f*w, .55f*h), 2.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun SleepTimerMenu(
    isLive: Boolean,
    timer: SleepTimerUiState,
    onCommand: (SleepTimerUiCommand) -> Unit,
    onPinned: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dismissMenus: Int = 0,
    onMenuOpenChanged: (PlayerPopup, Boolean) -> Unit = { _, _ -> },
) {
    var expanded by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf(30) }
    LaunchedEffect(expanded, customOpen) {
        onMenuOpenChanged(PlayerPopup.Timer, expanded || customOpen)
    }
    LaunchedEffect(dismissMenus) {
        if (dismissMenus > 0 && (expanded || customOpen)) {
            expanded = false
            customOpen = false
            onPinned(false)
        }
    }
    val customFocus = remember { FocusRequester() }
    LaunchedEffect(customOpen) { if (customOpen) runCatching { customFocus.requestFocus() } }
    val type = adaptiveType()
    Box {
        OverlayButton(
            IconKind.Timer,
            stringResource(R.string.sleep_timer_button),
            modifier,
            onClick = { expanded = true; onPinned(true) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false; onPinned(false) }) {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                if (timer.isActive) DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_timer_turn_off), color = AppColors.Ink, style = type.label) },
                    onClick = { expanded = false; onPinned(false); onCommand(SleepTimerUiPolicy.cancel()) },
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusFrame().testTag("sleepTimerCancel")
                )
                if (!isLive) DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_timer_end_of_item), color = AppColors.Ink, style = type.label) },
                    onClick = { expanded = false; onPinned(false); onCommand(SleepTimerUiPolicy.endOfItem()) },
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusFrame().testTag("sleepTimerEndOfItem")
                )
                SleepTimerUiPolicy.presets.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sleep_timer_preset, minutes), color = AppColors.Ink, style = type.label) },
                        onClick = { expanded = false; onPinned(false); onCommand(SleepTimerUiPolicy.preset(minutes)) },
                        modifier = Modifier.heightIn(min = 48.dp).tvFocusFrame().testTag("sleepTimerPreset$minutes")
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_timer_custom), color = AppColors.Ink, style = type.label) },
                    onClick = { expanded = false; customOpen = true; customMinutes = 30 },
                    modifier = Modifier.heightIn(min = 48.dp).tvFocusFrame().testTag("sleepTimerCustom")
                )
            }
        }
    }
    if (customOpen) Dialog(onDismissRequest = { customOpen = false; onPinned(false) }) {
        Column(
            Modifier.fillMaxWidth().background(AppColors.Elevated, RoundedCornerShape(16.dp)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.sleep_timer_custom), color = AppColors.Ink, style = type.title)
            Spacer(Modifier.height(12.dp))
            val decreaseLabel = stringResource(R.string.decrease)
            val increaseLabel = stringResource(R.string.increase)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextButton(
                    onClick = { customMinutes = (customMinutes - 5).coerceAtLeast(5) },
                    modifier = Modifier.size(48.dp).focusRequester(customFocus).tvFocusFrame()
                        .semantics { contentDescription = decreaseLabel },
                ) {
                    Text("−")
                }
                Text(stringResource(R.string.sleep_timer_preset, customMinutes), color = AppColors.Ink, style = type.title)
                TextButton(
                    onClick = { customMinutes = (customMinutes + 5).coerceAtMost(240) },
                    modifier = Modifier.size(48.dp).tvFocusFrame().semantics { contentDescription = increaseLabel },
                ) {
                    Text("+")
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { customOpen = false; onPinned(false); onCommand(SleepTimerUiPolicy.custom(customMinutes)) },
                modifier = Modifier.height(48.dp).tvFocusFrame()
            ) { Text(stringResource(R.string.sleep_timer_start)) }
        }
    }
}

@Composable
private fun TrackMenu(
    label: String, icon: IconKind, choices: List<TrackChoice>, onChoose: (Int) -> Unit,
    modifier: Modifier = Modifier, popup: PlayerPopup, dismissMenus: Int,
    onMenuOpenChanged: (PlayerPopup, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(dismissMenus) { if (dismissMenus > 0 && expanded) expanded = false }
    LaunchedEffect(expanded) { onMenuOpenChanged(popup, expanded) }
    val type = adaptiveType()
    Box {
        val selectedTrack = choices.firstOrNull { it.selected }?.label ?: stringResource(R.string.player_track_off)
        val selectedLabel = stringResource(
            if (icon == IconKind.Audio) R.string.player_audio_selected else R.string.player_subtitles_selected,
            selectedTrack
        )
        OverlayButton(icon, selectedLabel, modifier, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEachIndexed { index, choice ->
                    DropdownMenuItem(
                        text = { Text(if (choice.selected) "✓ ${choice.label}" else choice.label, color = AppColors.Ink, style = type.label) },
                        onClick = { expanded = false; onChoose(index) },
                        modifier = Modifier.tvFocusFrame().semantics { contentDescription = choice.label }.testTag("track$index")
                    )
                }
        }
    }
}

private fun formatPlayerTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1_000
    val minutes = seconds / 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60)
    else "%d:%02d".format(minutes, seconds % 60)
}

@Composable
private fun spokenDuration(ms: Long): String {
    val duration = DurationParts.fromMilliseconds(ms)
    return buildList {
        if (duration.hours > 0) add(pluralStringResource(R.plurals.duration_hours, duration.hours, duration.hours))
        if (duration.minutes > 0) add(pluralStringResource(R.plurals.duration_minutes, duration.minutes, duration.minutes))
        if (duration.seconds > 0 || isEmpty()) add(pluralStringResource(R.plurals.duration_seconds, duration.seconds, duration.seconds))
    }.joinToString(" ")
}
