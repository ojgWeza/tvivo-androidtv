package com.dev.Tvivo

import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.auth.SavedSource
import com.dev.Tvivo.auth.SourceSnapshot
import com.dev.Tvivo.home.ShellTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceStartupRouteTest {
    private val xtream = Credentials("example.invalid", 8080, "user", "password")
    private val xtreamSource = SavedSource.xtream(xtream)
    private val drive = SavedSource.drive("permission-id", null)

    @Test fun releaseFlagKeepsLegacyXtreamRouteEvenWhenDriveWasActive() {
        val route = sourceStartupRoute(SourceSnapshot(listOf(xtreamSource, drive), drive.id),
            xtream, false, ShellTab.Movies)
        assertEquals(Route.Main(xtream, ShellTab.Movies), route)
    }

    @Test fun enabledFlagUsesActiveDriveAndStableSourceKey() {
        val route = sourceStartupRoute(SourceSnapshot(listOf(xtreamSource, drive), drive.id),
            xtream, true, ShellTab.MyTvivo)
        assertTrue(route is Route.DriveHome)
        assertEquals(drive.id, (route as Route.DriveHome).session.id)
    }

    @Test fun enabledFlagKeepsXtreamDefaultWhenXtreamIsActive() {
        val route = sourceStartupRoute(SourceSnapshot(listOf(xtreamSource), xtreamSource.id),
            xtream, true, ShellTab.MyTvivo)
        assertEquals(Route.Main(xtream, ShellTab.MyTvivo), route)
    }
}
