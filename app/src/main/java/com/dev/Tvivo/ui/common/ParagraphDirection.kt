package com.dev.Tvivo.ui.common

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection

/** Resolves a paragraph from its first strong character rather than ambient LTR layout. */
internal fun paragraphDirection(text: String): TextDirection {
    for (character in text) {
        when (Character.getDirectionality(character)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return TextDirection.Ltr
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return TextDirection.Rtl
        }
    }
    return TextDirection.Ltr
}

internal fun paragraphAlignment(text: String): TextAlign = when (paragraphDirection(text)) {
    TextDirection.Rtl -> TextAlign.Right
    else -> TextAlign.Left
}
