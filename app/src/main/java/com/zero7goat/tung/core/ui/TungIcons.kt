package com.zero7goat.tung.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * TUNG's small set of stroke icons (24x24, drawn in code so no icon library is needed).
 * They are tinted by the surrounding content color.
 */
object TungIcons {

    val Notes: ImageVector by lazy {
        tungIcon("Notes") {
            outline(1.8f) {
                moveTo(6f, 3.5f); horizontalLineTo(18f); verticalLineTo(20.5f); horizontalLineTo(6f); close()
                moveTo(9f, 8.5f); horizontalLineTo(15f)
                moveTo(9f, 12f); horizontalLineTo(15f)
                moveTo(9f, 15.5f); horizontalLineTo(12.5f)
            }
        }
    }

    val Lock: ImageVector by lazy {
        tungIcon("Lock") {
            outline(1.8f) {
                moveTo(6f, 10.5f); horizontalLineTo(18f); verticalLineTo(20f); horizontalLineTo(6f); close()
                moveTo(8.5f, 10.5f); verticalLineTo(8f)
                arcTo(3.5f, 3.5f, 0f, false, true, 15.5f, 8f)
                verticalLineTo(10.5f)
                moveTo(12f, 14.2f); verticalLineTo(16.2f)
            }
        }
    }

    val Folder: ImageVector by lazy {
        tungIcon("Folder") {
            outline(1.8f) {
                moveTo(3.5f, 6.5f); horizontalLineTo(9.5f); lineTo(11.5f, 8.5f)
                horizontalLineTo(20.5f); verticalLineTo(18.5f); horizontalLineTo(3.5f); close()
            }
        }
    }

    val Sliders: ImageVector by lazy {
        tungIcon("Sliders") {
            outline(1.8f) {
                moveTo(4f, 8f); horizontalLineTo(12.5f)
                moveTo(17.5f, 8f); horizontalLineTo(20f)
                circle(15f, 8f, 2.5f)
                moveTo(4f, 16f); horizontalLineTo(6.5f)
                moveTo(11.5f, 16f); horizontalLineTo(20f)
                circle(9f, 16f, 2.5f)
            }
        }
    }

    val Plus: ImageVector by lazy {
        tungIcon("Plus") {
            outline(2f) {
                moveTo(12f, 5f); verticalLineTo(19f)
                moveTo(5f, 12f); horizontalLineTo(19f)
            }
        }
    }

    val Search: ImageVector by lazy {
        tungIcon("Search") {
            outline(1.8f) {
                circle(10.5f, 10.5f, 6f)
                moveTo(15f, 15f); lineTo(20f, 20f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        tungIcon("ChevronRight") {
            outline(2f) {
                moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f)
            }
        }
    }
    val ChevronLeft: ImageVector by lazy {
        tungIcon("ChevronLeft") {
            outline(2f) {
                moveTo(14.5f, 6f); lineTo(8.5f, 12f); lineTo(14.5f, 18f)
            }
        }
    }

    val Star: ImageVector by lazy {
        tungIcon("Star") { outline(1.8f) { star() } }
    }

    val StarFilled: ImageVector by lazy {
        tungIcon("StarFilled") { solid(1.8f) { star() } }
    }

    val Pin: ImageVector by lazy {
        tungIcon("Pin") {
            outline(1.8f) {
                pinBody()
                pinDetails()
            }
        }
    }

    val PinFilled: ImageVector by lazy {
        tungIcon("PinFilled") {
            solid(1.8f) { pinBody() }
            outline(1.8f) { pinDetails() }
        }
    }

    val Trash: ImageVector by lazy {
        tungIcon("Trash") {
            outline(1.8f) {
                moveTo(5f, 7f); horizontalLineTo(19f)
                moveTo(10f, 7f); verticalLineTo(4.5f); horizontalLineTo(14f); verticalLineTo(7f)
                moveTo(7f, 7f); lineTo(8f, 19.5f); horizontalLineTo(16f); lineTo(17f, 7f)
                moveTo(10.5f, 11f); verticalLineTo(16f)
                moveTo(13.5f, 11f); verticalLineTo(16f)
            }
        }
    }
    val Checkbox: ImageVector by lazy {
        tungIcon("Checkbox") {
            outline(1.8f) {
                moveTo(5f, 5f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(5f); close()
            }
        }
    }

    val CheckboxChecked: ImageVector by lazy {
        tungIcon("CheckboxChecked") {
            outline(1.8f) {
                moveTo(5f, 5f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(5f); close()
                moveTo(8.5f, 12.5f); lineTo(11f, 15f); lineTo(15.5f, 9.5f)
            }
        }
    }

    val Quote: ImageVector by lazy {
        tungIcon("Quote") {
            outline(1.8f) {
                moveTo(6f, 5f); verticalLineTo(19f)
                moveTo(10f, 8f); horizontalLineTo(19f)
                moveTo(10f, 12f); horizontalLineTo(17f)
                moveTo(10f, 16f); horizontalLineTo(19f)
            }
        }
    }

    val Link: ImageVector by lazy {
        tungIcon("Link") {
            outline(1.8f) {
                pill(2.5f, 9f, 13.5f, 15f)
                pill(10.5f, 9f, 21.5f, 15f)
            }
        }
    }

    val Highlight: ImageVector by lazy {
        tungIcon("Highlight") {
            outline(1.8f) {
                moveTo(14f, 4f); lineTo(20f, 10f); lineTo(11f, 19f); lineTo(5f, 13f); close()
                moveTo(4f, 21f); horizontalLineTo(20f)
            }
        }
    }
    val Undo: ImageVector by lazy {
        tungIcon("Undo") {
            outline(1.8f) {
                moveTo(9f, 6.5f); lineTo(5f, 10.5f); lineTo(9f, 14.5f)
                moveTo(5f, 10.5f); horizontalLineTo(14.5f)
                arcTo(4.5f, 4.5f, 0f, false, true, 14.5f, 19.5f)
                horizontalLineTo(11f)
            }
        }
    }

    val Redo: ImageVector by lazy {
        tungIcon("Redo") {
            outline(1.8f) {
                moveTo(15f, 6.5f); lineTo(19f, 10.5f); lineTo(15f, 14.5f)
                moveTo(19f, 10.5f); horizontalLineTo(9.5f)
                arcTo(4.5f, 4.5f, 0f, false, false, 9.5f, 19.5f)
                horizontalLineTo(13f)
            }
        }
    }

    val Image: ImageVector by lazy {
        tungIcon("Image") {
            outline(1.8f) {
                moveTo(4f, 5f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close()
                moveTo(4f, 16f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(15.5f, 12.5f); lineTo(20f, 17f)
                circle(15.5f, 8.5f, 1.4f)
            }
        }
    }

    val File: ImageVector by lazy {
        tungIcon("File") {
            outline(1.8f) {
                moveTo(6f, 3.5f); horizontalLineTo(13.5f); lineTo(18f, 8f)
                verticalLineTo(20.5f); horizontalLineTo(6f); close()
                moveTo(13.5f, 3.5f); verticalLineTo(8f); horizontalLineTo(18f)
            }
        }
    }
}

private fun tungIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()

private fun ImageVector.Builder.outline(width: Float, block: PathBuilder.() -> Unit) {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    )
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
}

/** Like [outline], but also fills the shape with the tint color. */
private fun ImageVector.Builder.solid(width: Float, block: PathBuilder.() -> Unit) {
    path(
        fill = SolidColor(Color.Black),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    )
}

private fun PathBuilder.star() {
    moveTo(12.00f, 3.80f)
    lineTo(14.41f, 9.48f)
    lineTo(20.56f, 10.02f)
    lineTo(15.90f, 14.07f)
    lineTo(17.29f, 20.08f)
    lineTo(12.00f, 16.90f)
    lineTo(6.71f, 20.08f)
    lineTo(8.10f, 14.07f)
    lineTo(3.44f, 10.02f)
    lineTo(9.59f, 9.48f)
    close()
}

private fun PathBuilder.pinBody() {
    moveTo(10f, 4f); verticalLineTo(10.5f); lineTo(7.5f, 14f)
    horizontalLineTo(16.5f); lineTo(14f, 10.5f); verticalLineTo(4f); close()
}

private fun PathBuilder.pinDetails() {
    moveTo(8.5f, 4f); horizontalLineTo(15.5f)
    moveTo(12f, 14f); verticalLineTo(20f)
}

/** A rounded "pill" outline: a rectangle whose short sides are half circles. */
private fun PathBuilder.pill(x1: Float, y1: Float, x2: Float, y2: Float) {
    val r = (y2 - y1) / 2f
    moveTo(x1 + r, y1)
    horizontalLineTo(x2 - r)
    arcTo(r, r, 0f, false, true, x2 - r, y2)
    horizontalLineTo(x1 + r)
    arcTo(r, r, 0f, false, true, x1 + r, y1)
    close()
}
