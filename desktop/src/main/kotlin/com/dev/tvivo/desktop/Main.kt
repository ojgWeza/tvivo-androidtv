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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
    var exitRequested by remember { mutableStateOf(false) }
    Window(onCloseRequest = { exitRequested = true }, title = "Tvivo desktop", state = rememberWindowState(placement = WindowPlacement.Fullscreen), undecorated = true) {
        MaterialTheme {
            DesktopApp(onExit = { exitRequested = true })
            if (exitRequested) AlertDialog(
                onDismissRequest = { exitRequested = false },
                title = { Text("Exit Tvivo?") },
                text = { Text("Your account and downloaded library will stay on this device.") },
                dismissButton = { OutlinedButton(onClick = { exitRequested = false }) { Text("Stay in Tvivo") } },
                confirmButton = { Button(onClick = ::exitApplication) { Text("Exit") } },
            )
        }
    }
}

@Composable
private fun DesktopApp(onExit: () -> Unit) {
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
        DesktopShell(credentials!!, onExit = onExit, onSignOut = {
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
