package com.dev.Tvivo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFieldPolicyTest {
    @Test fun backClearsAnyNonEmptyTextBeforeClosing() {
        assertEquals(SearchBackAction.Clear, searchBackAction("movie"))
        assertEquals(SearchBackAction.Clear, searchBackAction(" "))
    }

    @Test fun backClosesAnEmptyField() {
        assertEquals(SearchBackAction.Close, searchBackAction(""))
    }
}
