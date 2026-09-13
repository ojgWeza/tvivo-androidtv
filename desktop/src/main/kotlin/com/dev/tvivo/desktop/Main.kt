package com.dev.tvivo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.auth.ServerAddress
import com.dev.tvivo.desktop.auth.DesktopAuthRepository
import com.dev.tvivo.desktop.auth.WindowsCredentialsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Canvas
import java.awt.Color as AwtColor
import java.awt.event.HierarchyEvent
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Tvivo desktop", state = WindowState(width = 1000.dp, height = 720.dp)) {
        MaterialTheme {
            DesktopApp()
        }
    }
}

@Composable
private fun DesktopApp() {
    val store = remember { WindowsCredentialsStore() }
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var credentials by remember { mutableStateOf<Credentials?>(null) }
    LaunchedEffect(Unit) {
        credentials = withContext(Dispatchers.IO) { store.load() }
        loaded = true
    }
    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Opening Tvivo desktop…") }
    } else if (credentials == null) {
        SignInScreen { accepted ->
            scope.launch {
                withContext(Dispatchers.IO) { store.save(accepted) }
                credentials = accepted
            }
        }
    } else {
        PlayerScreen(credentials!!, onSignOut = {
            scope.launch {
                withContext(Dispatchers.IO) { store.wipe() }
                credentials = null
            }
        })
    }
}

@Composable
private fun SignInScreen(onAuthenticated: (Credentials) -> Unit) {
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Enter your provider details. They are encrypted for this Windows user.") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tvivo desktop", style = MaterialTheme.typography.headlineMedium)
            Text("Sign in", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(server, { server = it }, label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(message, style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = !submitting && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                onClick = {
                    val parsed = ServerAddress.parse(server)
                    if (parsed == null) { message = "Enter a valid server address."; return@Button }
                    submitting = true
                    message = "Checking account…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            DesktopAuthRepository().authenticate(Credentials(parsed.host, parsed.port, username.trim(), password, parsed.useHttps))
                        }
                        submitting = false
                        result.onSuccess(onAuthenticated).onFailure { message = "Sign-in failed. Check the server and account details." }
                    }
                },
            ) { Text(if (submitting) "Signing in…" else "Sign in") }
        }
    }
}

@Composable
private fun PlayerScreen(credentials: Credentials, onSignOut: () -> Unit) {
    var status by remember { mutableStateOf("Choose a local fixture.") }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    val surface = remember { Canvas().apply { background = AwtColor.BLACK } }
    val player = remember { LibVlcPlayer { update -> SwingUtilities.invokeLater { status = update } } }

    DisposableEffect(player) {
        var attempted = false
        fun initialise() {
            if (attempted || !surface.isDisplayable) return
            attempted = true
            player.initialise(surface).onFailure { status = "LibVLC needs setup: ${it.message}" }
        }
        val listener = java.awt.event.HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) initialise()
        }
        surface.addHierarchyListener(listener)
        initialise()
        onDispose { surface.removeHierarchyListener(listener); player.close() }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Tvivo desktop", style = MaterialTheme.typography.titleLarge)
                Text("${credentials.hostAndPort()} · $status", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
            SwingPanel(factory = { surface }, modifier = Modifier.fillMaxSize())
        }
        Text(selectedFile?.name ?: "No local fixture selected", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { chooseFixture()?.let { selectedFile = it; status = "Ready" } }) { Text("Open fixture") }
            Button(enabled = selectedFile != null, onClick = { selectedFile?.let { player.play(it).onFailure { error -> status = "Playback error: ${error.message}" } } }) { Text("Play") }
            OutlinedButton(onClick = { player.pause() }) { Text("Pause") }
            OutlinedButton(onClick = { player.stop() }) { Text("Stop") }
        }
        Slider(seekPosition, onValueChange = { seekPosition = it }, onValueChangeFinished = { player.seek(seekPosition) }, modifier = Modifier.fillMaxWidth().height(32.dp))
    }
}

private fun chooseFixture(): File? = JFileChooser().apply {
    dialogTitle = "Select local synthetic media fixture"
    fileFilter = FileNameExtensionFilter("POC fixtures (*.mp4, *.mkv, *.ts)", "mp4", "mkv", "ts")
}.let { chooser -> if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null }
