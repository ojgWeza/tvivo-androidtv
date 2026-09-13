package com.dev.tvivo.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.Canvas
import java.awt.Color
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Tvivo desktop playback POC") {
        MaterialTheme {
            var state by remember { mutableStateOf("Choose a local synthetic fixture.") }
            var selectedFile by remember { mutableStateOf<File?>(null) }
            var seekPosition by remember { mutableFloatStateOf(0f) }
            val surface = remember { Canvas().apply { background = Color.BLACK } }
            val player = remember { LibVlcPlayer { update -> SwingUtilities.invokeLater { state = update } } }

            DisposableEffect(player) {
                player.initialise(surface).onFailure { error -> state = "LibVLC unavailable: ${error.message}" }
                onDispose { player.close() }
            }

            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwingPanel(factory = { surface }, modifier = Modifier.fillMaxWidth().height(480.dp))
                Text(state)
                Text(selectedFile?.name ?: "No fixture selected")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { chooseFixture()?.let { selectedFile = it } }) { Text("Choose fixture") }
                    Button(onClick = { selectedFile?.let { player.play(it).onFailure { error -> state = "Playback error: ${error.message}" } } }) { Text("Play") }
                    Button(onClick = { player.pause() }) { Text("Pause") }
                    Button(onClick = { player.resume() }) { Text("Resume") }
                    Button(onClick = { player.stop() }) { Text("Stop") }
                    Button(onClick = ::exitApplication) { Text("Close") }
                }
                Slider(value = seekPosition, onValueChange = { seekPosition = it }, onValueChangeFinished = { player.seek(seekPosition) }, modifier = Modifier.height(32.dp))
            }
        }
    }
}

private fun chooseFixture(): File? = JFileChooser().apply {
    dialogTitle = "Select local synthetic media fixture"
    fileFilter = FileNameExtensionFilter("POC fixtures (*.mp4, *.mkv, *.ts)", "mp4", "mkv", "ts")
}.let { chooser -> if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null }
