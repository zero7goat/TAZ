package com.zero7goat.tung.features.notes.presentation

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

// Helpers that insert Markdown-style syntax into the note body at the cursor.
// They only edit text; nothing here knows about styling. Inside TextFieldState.edit { }
// the selection is set by assigning TextFieldBuffer.selection.

/**
 * Wraps the selection in [prefix] and [suffix]. With no selection, inserts the pair around
 * [placeholder] and selects the placeholder so typing replaces it.
 */
internal fun TextFieldState.wrapSelection(prefix: String, suffix: String, placeholder: String) {
    edit {
        val start = selection.min
        val end = selection.max
        if (start == end) {
            replace(start, end, prefix + placeholder + suffix)
            selection = TextRange(start + prefix.length, start + prefix.length + placeholder.length)
        } else {
            replace(end, end, suffix) // end first, so [start] stays valid
            replace(start, start, prefix)
            selection = TextRange(start + prefix.length, end + prefix.length)
        }
    }
}

/**
 * Adds [prefix] to every line touched by the selection, or removes it if every one of those
 * lines already starts with one of the [recognised] prefixes.
 */
internal fun TextFieldState.toggleLinePrefix(
    prefix: String,
    recognised: List<String> = listOf(prefix)
) {
    edit {
        val text = asCharSequence().toString()
        val start = selection.min
        val end = selection.max

        val blockStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        // A selection that ends right after a newline does not include the next line.
        val searchFrom = if (end > start && end > 0 && text[end - 1] == '\n') end - 1 else end
        val newlineAfter = text.indexOf('\n', searchFrom)
        val blockEnd = if (newlineAfter < 0) text.length else newlineAfter

        val lines = text.substring(blockStart, blockEnd).split('\n')
        val allHavePrefix = lines.all { line -> recognised.any { line.startsWith(it) } }
        val result = lines.joinToString("\n") { line ->
            val existing = recognised.firstOrNull { line.startsWith(it) }
            when {
                allHavePrefix -> line.removePrefix(existing ?: "")
                existing != null -> line
                else -> prefix + line
            }
        }
        replace(blockStart, blockEnd, result)
        selection = TextRange(blockStart + result.length)
    }
}

/**
 * Replaces the selection with [line] as a line of its own (adding line breaks before and after
 * where needed) and puts the cursor after it. Used for image references.
 */
internal fun TextFieldState.insertBlockLine(line: String) {
    edit {
        val text = asCharSequence()
        val start = selection.min
        val end = selection.max
        val before = if (start > 0 && text[start - 1] != '\n') "\n" else ""
        val after = if (end < text.length && text[end] == '\n') "" else "\n"
        val inserted = before + line + after
        replace(start, end, inserted)
        selection = TextRange(start + inserted.length)
    }
}

/**
 * Inserts a link. With a selection, the selection becomes the link text and the address is
 * selected for typing; with none, a "link text" placeholder is inserted and selected.
 */
internal fun TextFieldState.insertLink() {
    edit {
        val start = selection.min
        val end = selection.max
        val address = "https://"
        if (start == end) {
            val label = "link text"
            replace(start, end, "[$label]($address)")
            selection = TextRange(start + 1, start + 1 + label.length)
        } else {
            replace(end, end, "]($address)")
            replace(start, start, "[")
            val addressStart = end + 3 // after "[" and "]("
            selection = TextRange(addressStart, addressStart + address.length)
        }
    }
}
