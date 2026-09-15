package com.dev.tvivo.desktop.auth

import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.AppError
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class DesktopAuthRepository(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
) {
    fun authenticate(credentials: Credentials): Result<Credentials> = runCatching {
        val query = "username=${encode(credentials.username)}&password=${encode(credentials.password)}"
        val request = HttpRequest.newBuilder(URI.create("${credentials.playerApiUrl()}?$query"))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "${AppError.Unreachable}" }
        val root = JsonParser.parseString(response.body()).asJsonObject
        val user = root.getAsJsonObject("user_info") ?: error("${AppError.AuthFailed}")
        if (user.get("auth")?.asInt != 1) error("${AppError.AuthFailed}")
        if (!user.get("status")?.asString.equals("Active", ignoreCase = true)) error("${AppError.AccountExpired(null)}")
        val port = root.getAsJsonObject("server_info")?.get("port")?.asString?.toIntOrNull()
        credentials.copy(port = credentials.port ?: port)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
