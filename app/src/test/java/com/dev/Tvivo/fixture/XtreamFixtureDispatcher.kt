package com.dev.Tvivo.fixture

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/** Loopback-only API fixture. No media route is served in slice 0A. */
internal class XtreamFixtureDispatcher(
    var maxConnections: Int = 1,
    var port: Int = 80
) : Dispatcher() {
    var rateLimitAction: String? = null
    private var rateLimitPending = false

    fun rateLimitNext(action: String) {
        rateLimitAction = action
        rateLimitPending = true
    }

    fun resetFaults() {
        rateLimitAction = null
        rateLimitPending = false
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
        if (url.encodedPath.startsWith("/artwork/")) {
            val name = url.encodedPath.removePrefix("/artwork/")
            if (name !in setOf("channel.svg", "poster.svg")) return MockResponse().setResponseCode(404)
            return MockResponse()
                .addHeader("Content-Type", "image/svg+xml")
                .setBody(fixture("artwork/$name"))
        }
        if (url.encodedPath != "/player_api.php") return MockResponse().setResponseCode(404)
        if (url.queryParameter("username") != "fixture-user" ||
            url.queryParameter("password") != "fixture-pass"
        ) return MockResponse().setResponseCode(401)

        val action = url.queryParameter("action")
        if (rateLimitPending && action == rateLimitAction) {
            rateLimitPending = false
            return MockResponse().setResponseCode(429).addHeader("Retry-After", "2")
        }
        val name = when (action) {
            null -> "auth"
            "get_live_categories" -> "live_categories"
            "get_vod_categories" -> "vod_categories"
            "get_series_categories" -> "series_categories"
            "get_live_streams" -> "live_streams"
            "get_vod_streams" -> "vod_streams"
            "get_series" -> "series"
            "get_series_info" -> if (url.queryParameter("series_id") == "3002") {
                "series_info_empty"
            } else "series_info"
            "get_vod_info" -> "vod_info"
            else -> return MockResponse().setResponseCode(404)
        }
        val expectedCategory = when (name) {
            "live_streams" -> "101"
            "vod_streams" -> "201"
            "series" -> "301"
            else -> null
        }
        if (expectedCategory != null && url.queryParameter("category_id")
            !in listOf(null, expectedCategory)
        ) return MockResponse().setBody("[]")
        if (name == "vod_info" && url.queryParameter("vod_id") != "2001") {
            return MockResponse().setResponseCode(404)
        }
        if (name.startsWith("series_info") && url.queryParameter("series_id") !in
            setOf("3001", "3002")
        ) return MockResponse().setResponseCode(404)
        return MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody(
                fixture("$name.json")
                    .replace("{{MAX_CONNECTIONS}}", maxConnections.toString())
                    .replace("{{PORT}}", port.toString())
            )
    }

    companion object {
        fun fixture(name: String): String = requireNotNull(
            XtreamFixtureDispatcher::class.java.classLoader
                ?.getResourceAsStream("xtream-fixture/$name")
        ) { "Missing fixture: $name" }.bufferedReader().use { it.readText() }
    }
}
