package com.zero7goat.taz.core.ui

import androidx.compose.foundation.text.input.TextFieldState

/**
 * Replaces the whole text with [text] and puts the cursor after the last character.
 *
 * Uses only members documented on TextFieldBuffer: `replace` and `placeCursorBeforeCharAt`
 * (passing `length` places the cursor at the very end). It is the same operation as the
 * library's "set text and place cursor at end" extension, without needing that extension's import.
 */
fun TextFieldState.replaceAllText(text: String) {
    edit {
        replace(0, length, text)
        placeCursorBeforeCharAt(length)
    }
}
