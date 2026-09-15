package com.dev.Tvivo

import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.ui.home.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRouteTest {

    private val credentials = Credentials("example.invalid", null, "user", "password")

    @Test
    fun `Live TV tile enters the Live browse route`() =
        assertEquals(ContentType.LIVE, browseRoute(credentials, ContentType.LIVE).type)

    @Test
    fun `Movies tile enters the Movies browse route`() =
        assertEquals(ContentType.MOVIES, browseRoute(credentials, ContentType.MOVIES).type)

    @Test
    fun `Series tile enters the Series browse route`() =
        assertEquals(ContentType.SERIES, browseRoute(credentials, ContentType.SERIES).type)

    @Test
    fun `Home selection mutates route state to Browse`() {
        var route: Route = Route.Home(credentials)

        route = browseRoute(credentials, ContentType.MOVIES)

        assertTrue(route is Route.Browse)
        assertEquals(ContentType.MOVIES, (route as Route.Browse).type)
    }
}
