package com.zero7goat.taz.features.notes.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Note colours. The stored `colorIndex` points into [NoteColors]; index 0 means "no colour".
 *
 * Indices 1 to 5 are the original five colours (same hues as before), so notes that already have
 * a colour keep it. New colours are only ever added at the end, never inserted or reordered,
 * because the index is what the database and backups store.
 *
 * Each colour is a muted, mid-tone accent. Note text is never drawn on the accent itself: the
 * list and the editor use [noteTint], a light wash of the accent over the theme surface, so the
 * normal text colour stays readable in the light, dark and AMOLED themes.
 */
internal val NoteColors: List<Color?> = listOf(
    null,
    Color(0xFFD98C8C), // 1 Red (rose)
    Color(0xFFD9B26F), // 2 Amber
    Color(0xFF8FBF9F), // 3 Green (sage)
    Color(0xFF8CAFD9), // 4 Blue
    Color(0xFFB49CD9), // 5 Purple (lavender)
    Color(0xFF6FB8B0), // 6 Teal
    Color(0xFFE0A386), // 7 Peach
    Color(0xFFC9B48F), // 8 Sand
    Color(0xFFA8B07A), // 9 Olive
    Color(0xFF7FC0D8), // 10 Sky
    Color(0xFFC490B4), // 11 Plum
    Color(0xFF97A0AE)  // 12 Slate
)

/** Spoken names of [NoteColors], in the same order (also used in the spreadsheet export). */
internal val NoteColorNames: List<String> = listOf(
    "None", "Red", "Amber", "Green", "Blue", "Purple",
    "Teal", "Peach", "Sand", "Olive", "Sky", "Plum", "Slate"
)

/** The accent colour of [index], or null for "no colour" and for unknown indices. */
internal fun noteColor(index: Int): Color? = NoteColors.getOrNull(index)

/**
 * The background for a note of colour [index]: the accent mixed lightly into the current theme's
 * surface. Null for "no colour". Text on it uses the normal on-surface colour.
 */
@Composable
@ReadOnlyComposable
internal fun noteTint(index: Int): Color? {
    val accent = noteColor(index) ?: return null
    return noteTintFor(accent, MaterialTheme.colorScheme.surface)
}

/** The wash used by [noteTint]: a small share of [accent] over [surface]. Pure, for testing. */
internal fun noteTintFor(accent: Color, surface: Color): Color {
    val dark = surface.luminance() < 0.5f
    return lerp(surface, accent, if (dark) 0.20f else 0.16f)
}
