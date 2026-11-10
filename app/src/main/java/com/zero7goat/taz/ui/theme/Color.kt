package com.zero7goat.taz.ui.theme

import androidx.compose.ui.graphics.Color

// TAZ palette: neutral grayscale only. No accent hues, no gradients.
// The only non-gray colors are the error tones, kept for destructive actions.

// --- Neutral ramp (shared by light and dark) ---
internal val Black = Color(0xFF000000)
internal val White = Color(0xFFFFFFFF)
internal val Ink = Color(0xFF111111)

internal val Gray50 = Color(0xFFF7F7F7)
internal val Gray100 = Color(0xFFF2F2F2)
internal val Gray150 = Color(0xFFEDEDED)
internal val Gray200 = Color(0xFFE5E5E5)
internal val Gray250 = Color(0xFFD4D4D4)
internal val Gray300 = Color(0xFFC7C7C7)
internal val Gray350 = Color(0xFFA0A0A0)
internal val Gray400 = Color(0xFF8A8A8A)
internal val Gray450 = Color(0xFF6E6E6E)
internal val Gray500 = Color(0xFF6B6B6B)
internal val Gray600 = Color(0xFF4A4A4A)
internal val Gray650 = Color(0xFF3A3A3A)
internal val Gray700 = Color(0xFF2C2C2C)
internal val Gray750 = Color(0xFF242424)
internal val Gray800 = Color(0xFF1C1C1E)
internal val Gray850 = Color(0xFF181818)
internal val Gray900 = Color(0xFF121212)
internal val Gray950 = Color(0xFF0D0D0D)

// --- AMOLED-only surfaces (true black base, very subtle steps above it) ---
internal val AmoledSurfaceLow = Color(0xFF0A0A0A)
internal val AmoledSurfaceContainer = Color(0xFF111111)
internal val AmoledSurfaceHigh = Color(0xFF1A1A1A)
internal val AmoledSurfaceHighest = Color(0xFF222222)
internal val AmoledSurfaceBright = Color(0xFF262626)
internal val AmoledOutlineVariant = Color(0xFF2E2E2E)

// --- Error tones ---
internal val ErrorLight = Color(0xFFB3261E)
internal val ErrorContainerLight = Color(0xFFF9DEDC)
internal val OnErrorContainerLight = Color(0xFF410E0B)
internal val ErrorDark = Color(0xFFF2B8B5)
internal val OnErrorDark = Color(0xFF601410)
internal val ErrorContainerDark = Color(0xFF8C1D18)
internal val OnErrorContainerDark = Color(0xFFF9DEDC)
