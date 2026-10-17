package com.dev.Tvivo.home

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleDisplayPolicyTest {
    @Test fun `leading quality does not erase a numeric title`() {
        assertEquals(TitleDisplayPolicy.Result("1992", "4K"), TitleDisplayPolicy.clean("4K 1992"))
    }

    @Test fun `leading quality is removed when a title remains`() {
        assertEquals(TitleDisplayPolicy.Result("A Quiet Place", "4K"),
            TitleDisplayPolicy.clean("4K A Quiet Place"))
    }

    @Test fun `quality token alone remains a valid title`() {
        assertEquals(TitleDisplayPolicy.Result("HD", null), TitleDisplayPolicy.clean("HD"))
    }

    @Test fun `numeric title remains intact`() {
        assertEquals(TitleDisplayPolicy.Result("1917", null), TitleDisplayPolicy.clean("1917"))
    }

    @Test fun `trailing quality preserves numeric title`() {
        assertEquals(TitleDisplayPolicy.Result("2012", "HD"), TitleDisplayPolicy.clean("2012 HD"))
    }
}
