package com.dev.tvivo.desktop.auth

import com.dev.Tvivo.auth.Credentials
import com.google.gson.Gson
import com.sun.jna.platform.win32.Crypt32Util
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Credentials are encrypted by the current Windows user's DPAPI key before disk write. */
internal class WindowsCredentialsStore(
    private val file: Path = Path.of(System.getenv("APPDATA") ?: ".", "Tvivo", "credentials.dat"),
) {
    private val gson = Gson()

    fun load(): Credentials? = runCatching {
        val protected = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII))
        gson.fromJson(String(Crypt32Util.cryptUnprotectData(protected), StandardCharsets.UTF_8), Credentials::class.java)
    }.getOrNull()

    fun save(credentials: Credentials) {
        Files.createDirectories(file.parent)
        val protected = Crypt32Util.cryptProtectData(gson.toJson(credentials).toByteArray(StandardCharsets.UTF_8))
        Files.writeString(file, Base64.getEncoder().encodeToString(protected), StandardCharsets.US_ASCII)
    }

    fun wipe() {
        Files.deleteIfExists(file)
    }
}
