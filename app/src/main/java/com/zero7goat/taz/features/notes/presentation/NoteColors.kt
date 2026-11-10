package com.zero7goat.taz.features.notes.presentation

import androidx.compose.ui.graphics.Color

/** Muted note colors. Index 0 means "no color". The stored colorIndex points into this list. */
internal val NoteColors: List<Color?> = listOf(
    null,
    Color(0xFFD98C8C),
    Color(0xFFD9B26F),
    Color(0xFF8FBF9F),
    Color(0xFF8CAFD9),
    Color(0xFFB49CD9)
)

/** Spoken names of [NoteColors], in the same order. */
internal val NoteColorNames: List<String> = listOf("None", "Red", "Amber", "Green", "Blue", "Purple")

internal fun noteColor(index: Int): Color? = NoteColors.getOrNull(index)
