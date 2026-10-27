package com.zero7goat.tung.core.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.zero7goat.tung.R
import com.zero7goat.tung.core.settings.DEFAULT_FONT_KEY

/**
 * The fonts TUNG can use. Every font file is bundled in the app (res/font), so nothing is ever
 * downloaded. Licenses are in assets/licenses/fonts.txt. Adding a font means adding its files to
 * res/font and one entry here.
 */
object AppFonts {

    class Option(val key: String, val label: String, val family: FontFamily)

    private val poppins = FontFamily(
        Font(R.font.poppins_regular, FontWeight.Normal),
        Font(R.font.poppins_bold, FontWeight.Bold),
        Font(R.font.poppins_italic, FontWeight.Normal, FontStyle.Italic)
    )

    private val liberationSerif = FontFamily(
        Font(R.font.liberation_serif_regular, FontWeight.Normal),
        Font(R.font.liberation_serif_bold, FontWeight.Bold),
        Font(R.font.liberation_serif_italic, FontWeight.Normal, FontStyle.Italic)
    )

    private val dejavuMono = FontFamily(
        Font(R.font.dejavu_sans_mono_regular, FontWeight.Normal),
        Font(R.font.dejavu_sans_mono_bold, FontWeight.Bold),
        Font(R.font.dejavu_sans_mono_oblique, FontWeight.Normal, FontStyle.Italic)
    )

    val options: List<Option> = listOf(
        Option(DEFAULT_FONT_KEY, "System default", FontFamily.Default),
        Option("poppins", "Poppins", poppins),
        Option("liberation_serif", "Liberation Serif", liberationSerif),
        Option("dejavu_mono", "DejaVu Sans Mono", dejavuMono)
    )

    fun familyFor(key: String): FontFamily = options.firstOrNull { it.key == key }?.family ?: FontFamily.Default

    fun labelFor(key: String): String = options.firstOrNull { it.key == key }?.label ?: options.first().label
}
