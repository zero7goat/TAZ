package com.zero7goat.taz.core.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.R
import com.zero7goat.taz.ui.theme.LocalDarkTheme

/** Width divided by height of both logo bitmaps (517 x 640 px), so the logo is never stretched. */
private const val LogoAspectRatio = 517f / 640f

/**
 * The TAZ logo for the theme in effect: the black logo on light themes and the white logo on
 * Dark and AMOLED. It follows the theme actually being drawn (including System on a dark device),
 * and cross-fades when the theme changes, with no restart. Both are transparent PNGs, drawn with
 * their own aspect ratio and no tint, scaling or redrawing.
 *
 * Pass a null [contentDescription] where the logo is decorative.
 */
@Composable
fun TazLogo(
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    contentDescription: String? = "TAZ logo"
) {
    val dark = LocalDarkTheme.current
    Crossfade(targetState = dark, modifier = modifier, animationSpec = tween(200), label = "logo") { useDark ->
        Image(
            painter = painterResource(if (useDark) R.drawable.taz_logo_white else R.drawable.taz_logo_black),
            contentDescription = contentDescription,
            modifier = Modifier
                .height(height)
                .aspectRatio(LogoAspectRatio, matchHeightConstraintsFirst = true),
            contentScale = ContentScale.Fit
        )
    }
}
