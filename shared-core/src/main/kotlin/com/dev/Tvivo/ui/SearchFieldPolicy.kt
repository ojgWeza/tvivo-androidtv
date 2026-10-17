package com.dev.Tvivo.ui

enum class SearchBackAction { Clear, Close }

/** The same two-step Back contract is used by Browse filters and the Search tab. */
fun searchBackAction(text: String): SearchBackAction =
    if (text.isNotEmpty()) SearchBackAction.Clear else SearchBackAction.Close
