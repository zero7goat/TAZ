package com.zero7goat.taz.branding

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the logo resources against being distorted, losing transparency or being clipped by launcher masks. */
class LogoAssetsTest {

    private fun load(name: String): BufferedImage {
        val file = File("src/main/res/drawable-nodpi/$name")
        assertTrue("Missing resource: ${file.absolutePath}", file.isFile)
        return requireNotNull(ImageIO.read(file)) { "Not a readable image: $name" }
    }

    private fun alpha(image: BufferedImage, x: Int, y: Int) = image.getRGB(x, y) ushr 24

    @Test
    fun inAppLogosAreTransparentAndShareTheSameSize() {
        val black = load("taz_logo_black.png")
        val white = load("taz_logo_white.png")
        assertTrue(black.colorModel.hasAlpha())
        assertTrue(white.colorModel.hasAlpha())
        assertEquals(black.width, white.width)
        assertEquals(black.height, white.height)
        for (image in listOf(black, white)) {
            assertEquals("corner must be transparent", 0, alpha(image, 0, 0))
            assertEquals("corner must be transparent", 0, alpha(image, image.width - 1, image.height - 1))
        }
    }

    @Test
    fun inAppLogosKeepTheOriginalAspectRatio() {
        // The supplied artwork is 2198 x 2719 px.
        val original = 2198.0 / 2719.0
        for (name in listOf("taz_logo_black.png", "taz_logo_white.png")) {
            val image = load(name)
            assertTrue("$name is distorted", abs(image.width.toDouble() / image.height - original) < 0.002)
        }
    }

    @Test
    fun inAppLogosAreSingleColourWithNoFringing() {
        // Black logo is pure black; the white logo is the supplied off-white (243, 243, 244).
        val expected = mapOf("taz_logo_black.png" to Triple(0, 0, 0), "taz_logo_white.png" to Triple(243, 243, 244))
        for ((name, rgb) in expected) {
            val image = load(name)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                if (argb ushr 24 == 255) {
                    assertEquals("$name colour at ($x,$y)", Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF), rgb)
                }
            }
        }
    }

    @Test
    fun launcherLogoStaysInsideTheAdaptiveIconSafeZone() {
        // 108dp canvas at xxxhdpi = 432 px; launchers may clip everything outside the central 66dp circle (132 px radius).
        for (name in listOf("taz_launcher_foreground.png", "taz_launcher_monochrome.png")) {
            val image = load(name)
            assertEquals(432, image.width)
            assertEquals(432, image.height)
            var farthest = 0.0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                if (alpha(image, x, y) > 16) farthest = maxOf(farthest, hypot(x + 0.5 - 216.0, y + 0.5 - 216.0))
            }
            assertTrue("$name reaches ${farthest}px from the centre; the safe radius is 132px", farthest <= 132.0)
            assertTrue("$name should not be empty", farthest > 40.0)
        }
    }
}
