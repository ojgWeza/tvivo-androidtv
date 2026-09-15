package com.dev.tvivo.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dev.tvivo.desktop.catalog.DesktopItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.graphics.Color
import java.net.URL

// Theme colors
internal val Background = Color(0xFF0A1619)
internal val Elevated = Color(0xFF163036)
internal val Line = Color(0xFF1E4149)
internal val Ink = Color(0xFFE8F1F2)
internal val Dim = Color(0xFF93ACB1)

@Composable
internal fun IdleEntryScreen(items: List<DesktopItem>, onExit: (String) -> Unit) {
    var index by remember(items) { mutableStateOf(0) }
    LaunchedEffect(items) {
        if (items.size < 2) return@LaunchedEffect
        while (true) {
            delay(8_000)
            index = (index + 1) % items.size
            idleTrace(if (index == 0) "idle rotation cycle restart" else "idle rotation advance")
        }
    }
    Box(Modifier.fillMaxSize().background(Background.copy(alpha = .6f)).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press || event.type == PointerEventType.Move) {
                    event.changes.forEach { it.consume() }
                    onExit("mouse")
                }
            }
        }
    }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(768.dp).height(576.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
            Text("TVIVO", style = MaterialTheme.typography.headlineLarge, color = Ink)
            Text("Device is idle", style = MaterialTheme.typography.bodyLarge, color = Dim)
            items.getOrNull(index)?.let { IdleRotationCard(it) }
        }
    }
}

@Composable private fun IdleRotationCard(item: DesktopItem) {
    AnimatedContent(item, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "idle rotation") { card ->
        var bitmap by remember(card.id) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(card.artwork) { bitmap = card.artwork?.takeIf(String::isNotBlank)?.let { url -> withContext(Dispatchers.IO) { runCatching { SkiaImage.makeFromEncoded(URL(url).readBytes()).toComposeImageBitmap() }.getOrNull() } } }
        val shape = RoundedCornerShape(12.dp)
        Box(Modifier.width(220.dp).height(330.dp).clip(shape).border(1.dp, Line, shape).background(Elevated), contentAlignment = Alignment.BottomCenter) {
            bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(card.title, color = Ink, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, Background.copy(alpha = .94f)))).padding(12.dp))
        }
    }
}
