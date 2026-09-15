package com.dev.Tvivo.data

import com.dev.Tvivo.auth.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamUrlBuilderTest {

    private val credentials = Credentials(
        host = "panel.example.com",
        port = 8080,
        username = "user1",
        password = "pass1"
    )

    @Test
    fun `movie url uses container_extension`() {
        assertEquals(
            "http://panel.example.com:8080/movie/user1/pass1/505439.mkv",
            StreamUrlBuilder.movie(credentials, 505439, "mkv")
        )
    }

    @Test
    fun `live url uses ext not container_extension`() {
        assertEquals(
            "http://panel.example.com:8080/live/user1/pass1/357057.ts",
            StreamUrlBuilder.live(credentials, 357057, "ts")
        )
    }

    @Test
    fun `episode url uses the episode id`() {
        assertEquals(
            "http://panel.example.com:8080/series/user1/pass1/533032.mkv",
            StreamUrlBuilder.episode(credentials, "533032", "mkv")
        )
    }

    @Test
    fun `https is honoured`() {
        val secure = credentials.copy(useHttps = true, port = 8443)
        assertEquals(
            "https://panel.example.com:8443/movie/user1/pass1/1.mp4",
            StreamUrlBuilder.movie(secure, 1, "mp4")
        )
    }

    @Test
    fun `missing port omits the colon rather than defaulting to 80`() {
        val noPort = credentials.copy(port = null)
        assertEquals(
            "http://panel.example.com/movie/user1/pass1/1.mp4",
            StreamUrlBuilder.movie(noPort, 1, "mp4")
        )
    }

    @Test
    fun `ipv6 host is bracketed`() {
        val ipv6 = credentials.copy(host = "2001:db8::1")
        assertEquals(
            "http://[2001:db8::1]:8080/movie/user1/pass1/1.mp4",
            StreamUrlBuilder.movie(ipv6, 1, "mp4")
        )
    }

    @Test
    fun `credentials with url-unsafe characters are encoded`() {
        val awkward = credentials.copy(username = "user name", password = "p@ss/word")
        val url = StreamUrlBuilder.movie(awkward, 1, "mp4")
        assertEquals(
            "http://panel.example.com:8080/movie/user%20name/p%40ss%2Fword/1.mp4",
            url
        )
    }

    @Test
    fun `extension is normalised when the panel returns a leading dot`() {
        assertEquals(
            "http://panel.example.com:8080/movie/user1/pass1/1.mkv",
            StreamUrlBuilder.movie(credentials, 1, ".mkv")
        )
    }

    @Test
    fun `empty extension falls back rather than producing a trailing dot`() {
        assertEquals(
            "http://panel.example.com:8080/movie/user1/pass1/1.ts",
            StreamUrlBuilder.movie(credentials, 1, "")
        )
    }

    /**
     * ExoPlayer echoes the full URI in its error messages, so this is the difference
     * between a pasted log being safe and it disclosing the account password.
     */
    @Test
    fun `redact removes both credential segments`() {
        val url = StreamUrlBuilder.movie(credentials, 505439, "mkv")
        val redacted = StreamUrlBuilder.redact(url)

        assertEquals("http://panel.example.com:8080/movie/***/***/505439.mkv", redacted)
        assertFalse(redacted.contains("user1"))
        assertFalse(redacted.contains("pass1"))
    }

    @Test
    fun `redact works on live and series paths too`() {
        assertFalse(
            StreamUrlBuilder.redact(StreamUrlBuilder.live(credentials, 1, "ts")).contains("pass1")
        )
        assertFalse(
            StreamUrlBuilder.redact(StreamUrlBuilder.episode(credentials, "1", "mkv"))
                .contains("pass1")
        )
    }

    @Test
    fun `redact handles an error message with surrounding text`() {
        val message =
            "Source error: http://panel.example.com:8080/movie/user1/pass1/1.mkv returned 403"
        val redacted = StreamUrlBuilder.redact(message)

        assertFalse(redacted.contains("user1"))
        assertFalse(redacted.contains("pass1"))
    }
}
