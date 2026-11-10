package com.zero7goat.taz.features.notes.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteColorsTest {

    @Test
    fun existingColourIndicesKeepTheirColoursAndNames() {
        // These five were stored by earlier versions; they must never change meaning.
        val original = listOf(0xFFD98C8C, 0xFFD9B26F, 0xFF8FBF9F, 0xFF8CAFD9, 0xFFB49CD9)
        original.forEachIndexed { i, argb -> assertEquals(Color(argb), NoteColors[i + 1]) }
        assertEquals(listOf("None", "Red", "Amber", "Green", "Blue", "Purple"), NoteColorNames.take(6))
        assertNull(NoteColors[0])
    }

    @Test
    fun paletteIsWiderAndEveryColourHasAName() {
        assertTrue(NoteColors.size >= 12)
        assertEquals(NoteColors.size, NoteColorNames.size)
        assertEquals(NoteColorNames.size, NoteColorNames.toSet().size)
    }

    @Test
    fun unknownIndicesFallBackToNoColour() {
        assertNull(noteColor(-1))
        assertNull(noteColor(999))
    }

    @Test
    fun noteTextStaysReadableOnEveryTintInEveryTheme() {
        // surface to on-surface pairs of the light, dark and AMOLED themes.
        val themes = listOf(
            Color(0xFFFFFFFF) to Color(0xFF111111),
            Color(0xFF121212) to Color(0xFFF2F2F2),
            Color(0xFF000000) to Color(0xFFF2F2F2)
        )
        for ((surface, onSurface) in themes) {
            for (accent in NoteColors.filterNotNull()) {
                val tint = noteTintFor(accent, surface)
                val ratio = contrast(tint, onSurface)
                assertTrue("contrast $ratio for $accent on $surface", ratio >= 7.0) // WCAG AAA
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb).toDouble()
    }
}
