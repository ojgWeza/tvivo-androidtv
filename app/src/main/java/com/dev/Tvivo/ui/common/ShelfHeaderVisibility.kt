package com.dev.Tvivo.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.relocation.bringIntoViewRequester

/** Keeps a shelf heading, and optionally the page heading, visible with its focused card. */
@OptIn(ExperimentalFoundationApi::class)
class ShelfHeaderVisibility internal constructor(
    val shelfHeading: BringIntoViewRequester,
    private val screenHeading: BringIntoViewRequester?
) {
    suspend fun revealShelf() {
        shelfHeading.bringIntoView()
    }

    suspend fun revealPageHeading() {
        screenHeading?.bringIntoView()
    }

    fun shelfHeadingModifier(): Modifier = Modifier.bringIntoViewRequester(shelfHeading)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun rememberShelfHeaderVisibility(
    screenHeading: BringIntoViewRequester? = null
): ShelfHeaderVisibility = remember(screenHeading) {
    ShelfHeaderVisibility(BringIntoViewRequester(), screenHeading)
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.screenHeadingRequester(requester: BringIntoViewRequester): Modifier =
    bringIntoViewRequester(requester)
