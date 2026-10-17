package com.dev.Tvivo.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellNavigationTest {
    @Test fun backFromOtherTopLevelTabsReturnsToMyTvivo() {
        ShellTab.entries.filter { it != ShellTab.MyTvivo }.forEach {
            assertEquals(ShellNavigation.BackAction.ReturnToMyTvivo,
                ShellNavigation.backFromTopLevel(it))
        }
    }

    @Test fun backFromMyTvivoRequestsExitConfirmation() {
        assertEquals(ShellNavigation.BackAction.ShowExitConfirmation,
            ShellNavigation.backFromTopLevel(ShellTab.MyTvivo))
    }
}
