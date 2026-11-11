package com.zero7goat.taz.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * TAZ's small set of stroke icons (24x24, drawn in code so no icon library is needed).
 * They are tinted by the surrounding content color.
 */
object TazIcons {

    val Notes: ImageVector by lazy {
        tazIcon("Notes") {
            outline(1.8f) {
                moveTo(6f, 3.5f); horizontalLineTo(18f); verticalLineTo(20.5f); horizontalLineTo(6f); close()
                moveTo(9f, 8.5f); horizontalLineTo(15f)
                moveTo(9f, 12f); horizontalLineTo(15f)
                moveTo(9f, 15.5f); horizontalLineTo(12.5f)
            }
        }
    }

    val Lock: ImageVector by lazy {
        tazIcon("Lock") {
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
        tazIcon("Folder") {
            outline(1.8f) {
                moveTo(3.5f, 6.5f); horizontalLineTo(9.5f); lineTo(11.5f, 8.5f)
                horizontalLineTo(20.5f); verticalLineTo(18.5f); horizontalLineTo(3.5f); close()
            }
        }
    }

    val Sliders: ImageVector by lazy {
        tazIcon("Sliders") {
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
        tazIcon("Plus") {
            outline(1.8f) {
                moveTo(12f, 5f); verticalLineTo(19f)
                moveTo(5f, 12f); horizontalLineTo(19f)
            }
        }
    }

    val Search: ImageVector by lazy {
        tazIcon("Search") {
            outline(1.8f) {
                circle(10.5f, 10.5f, 6f)
                moveTo(15f, 15f); lineTo(20f, 20f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        tazIcon("ChevronRight") {
            outline(1.8f) {
                moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f)
            }
        }
    }
    val ChevronLeft: ImageVector by lazy {
        tazIcon("ChevronLeft") {
            outline(1.8f) {
                moveTo(14.5f, 6f); lineTo(8.5f, 12f); lineTo(14.5f, 18f)
            }
        }
    }

    val Star: ImageVector by lazy {
        tazIcon("Star") { outline(1.8f) { star() } }
    }

    val StarFilled: ImageVector by lazy {
        tazIcon("StarFilled") { solid(1.8f) { star() } }
    }

    val Pin: ImageVector by lazy {
        tazIcon("Pin") {
            outline(1.8f) {
                pinBody()
                pinDetails()
            }
        }
    }

    val PinFilled: ImageVector by lazy {
        tazIcon("PinFilled") {
            solid(1.8f) { pinBody() }
            outline(1.8f) { pinDetails() }
        }
    }

    val Trash: ImageVector by lazy {
        tazIcon("Trash") {
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
        tazIcon("Checkbox") {
            outline(1.8f) {
                moveTo(5f, 5f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(5f); close()
            }
        }
    }

    val CheckboxChecked: ImageVector by lazy {
        tazIcon("CheckboxChecked") {
            outline(1.8f) {
                moveTo(5f, 5f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(5f); close()
                moveTo(8.5f, 12.5f); lineTo(11f, 15f); lineTo(15.5f, 9.5f)
            }
        }
    }

    val Quote: ImageVector by lazy {
        tazIcon("Quote") {
            outline(1.8f) {
                moveTo(6f, 5f); verticalLineTo(19f)
                moveTo(10f, 8f); horizontalLineTo(19f)
                moveTo(10f, 12f); horizontalLineTo(17f)
                moveTo(10f, 16f); horizontalLineTo(19f)
            }
        }
    }

    val Link: ImageVector by lazy {
        tazIcon("Link") {
            outline(1.8f) {
                pill(2.5f, 9f, 13.5f, 15f)
                pill(10.5f, 9f, 21.5f, 15f)
            }
        }
    }

    val Highlight: ImageVector by lazy {
        tazIcon("Highlight") {
            outline(1.8f) {
                moveTo(14f, 4f); lineTo(20f, 10f); lineTo(11f, 19f); lineTo(5f, 13f); close()
                moveTo(4f, 21f); horizontalLineTo(20f)
            }
        }
    }
    val Undo: ImageVector by lazy {
        tazIcon("Undo") {
            outline(1.8f) {
                moveTo(9f, 6.5f); lineTo(5f, 10.5f); lineTo(9f, 14.5f)
                moveTo(5f, 10.5f); horizontalLineTo(14.5f)
                arcTo(4.5f, 4.5f, 0f, false, true, 14.5f, 19.5f)
                horizontalLineTo(11f)
            }
        }
    }

    val Redo: ImageVector by lazy {
        tazIcon("Redo") {
            outline(1.8f) {
                moveTo(15f, 6.5f); lineTo(19f, 10.5f); lineTo(15f, 14.5f)
                moveTo(19f, 10.5f); horizontalLineTo(9.5f)
                arcTo(4.5f, 4.5f, 0f, false, false, 9.5f, 19.5f)
                horizontalLineTo(13f)
            }
        }
    }

    val Image: ImageVector by lazy {
        tazIcon("Image") {
            outline(1.8f) {
                moveTo(4f, 5f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close()
                moveTo(4f, 16f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(15.5f, 12.5f); lineTo(20f, 17f)
                circle(15.5f, 8.5f, 1.4f)
            }
        }
    }

    val File: ImageVector by lazy {
        tazIcon("File") {
            outline(1.8f) {
                moveTo(6f, 3.5f); horizontalLineTo(13.5f); lineTo(18f, 8f)
                verticalLineTo(20.5f); horizontalLineTo(6f); close()
                moveTo(13.5f, 3.5f); verticalLineTo(8f); horizontalLineTo(18f)
            }
        }
    }

    val Eye: ImageVector by lazy {
        tazIcon("Eye") {
            outline(1.8f) {
                moveTo(2.5f, 12f)
                curveTo(4.8f, 7.6f, 8.2f, 5.5f, 12f, 5.5f)
                curveTo(15.8f, 5.5f, 19.2f, 7.6f, 21.5f, 12f)
                curveTo(19.2f, 16.4f, 15.8f, 18.5f, 12f, 18.5f)
                curveTo(8.2f, 18.5f, 4.8f, 16.4f, 2.5f, 12f)
                close()
                circle(12f, 12f, 3f)
            }
        }
    }

    val EyeOff: ImageVector by lazy {
        tazIcon("EyeOff") {
            outline(1.8f) {
                moveTo(9.9f, 5.8f)
                curveTo(10.6f, 5.6f, 11.3f, 5.5f, 12f, 5.5f)
                curveTo(15.8f, 5.5f, 19.2f, 7.6f, 21.5f, 12f)
                curveTo(20.8f, 13.3f, 20f, 14.4f, 19.1f, 15.3f)
                moveTo(16.2f, 17.4f)
                curveTo(14.9f, 18.1f, 13.5f, 18.5f, 12f, 18.5f)
                curveTo(8.2f, 18.5f, 4.8f, 16.4f, 2.5f, 12f)
                curveTo(3.6f, 9.9f, 5f, 8.3f, 6.6f, 7.2f)
                moveTo(9.9f, 9.9f)
                curveTo(9.3f, 10.5f, 9f, 11.2f, 9f, 12f)
                curveTo(9f, 13.7f, 10.3f, 15f, 12f, 15f)
                curveTo(12.8f, 15f, 13.5f, 14.7f, 14.1f, 14.1f)
                moveTo(3.5f, 3.5f); lineTo(20.5f, 20.5f)
            }
        }
    }

    val FolderPlus: ImageVector by lazy {
        tazIcon("FolderPlus") {
            outline(1.8f) {
                moveTo(3.5f, 6.5f); horizontalLineTo(9.5f); lineTo(11.5f, 8.5f)
                horizontalLineTo(20.5f); verticalLineTo(18.5f); horizontalLineTo(3.5f); close()
                moveTo(12f, 11f); verticalLineTo(16f)
                moveTo(9.5f, 13.5f); horizontalLineTo(14.5f)
            }
        }
    }

    val FolderMove: ImageVector by lazy {
        tazIcon("FolderMove") {
            outline(1.8f) {
                moveTo(3.5f, 6.5f); horizontalLineTo(9.5f); lineTo(11.5f, 8.5f)
                horizontalLineTo(20.5f); verticalLineTo(18.5f); horizontalLineTo(3.5f); close()
                moveTo(9f, 13.5f); horizontalLineTo(15f)
                moveTo(12.5f, 11f); lineTo(15f, 13.5f); lineTo(12.5f, 16f)
            }
        }
    }

    val More: ImageVector by lazy {
        tazIcon("More") {
            solid(1.2f) {
                circle(12f, 5.5f, 1.1f)
                circle(12f, 12f, 1.1f)
                circle(12f, 18.5f, 1.1f)
            }
        }
    }

    val Close: ImageVector by lazy {
        tazIcon("Close") {
            outline(1.8f) {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }
    }

    val Check: ImageVector by lazy {
        tazIcon("Check") {
            outline(1.8f) {
                moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f)
            }
        }
    }

    val SelectAll: ImageVector by lazy {
        tazIcon("SelectAll") {
            outline(1.8f) {
                moveTo(4f, 4f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(4f); close()
                moveTo(8f, 12.3f); lineTo(10.8f, 15f); lineTo(16f, 9.5f)
            }
        }
    }

    val Shield: ImageVector by lazy {
        tazIcon("Shield") {
            outline(1.8f) {
                moveTo(12f, 3.5f); lineTo(19f, 6.2f); verticalLineTo(11.5f)
                curveTo(19f, 15.6f, 16.1f, 19f, 12f, 20.5f)
                curveTo(7.9f, 19f, 5f, 15.6f, 5f, 11.5f)
                verticalLineTo(6.2f); close()
                moveTo(9f, 12f); lineTo(11.2f, 14.2f); lineTo(15.2f, 10f)
            }
        }
    }

    val Key: ImageVector by lazy {
        tazIcon("Key") {
            outline(1.8f) {
                circle(8f, 15.5f, 3.8f)
                moveTo(10.7f, 12.8f); lineTo(19.5f, 4f)
                moveTo(16.5f, 7f); lineTo(19f, 9.5f)
                moveTo(14.2f, 9.3f); lineTo(16.2f, 11.3f)
            }
        }
    }

    // ---------- added in 1.2: tags, categories, fields, viewer ----------

    val Tag: ImageVector by lazy {
        tazIcon("Tag") {
            outline(1.8f) {
                moveTo(3.5f, 4.5f); verticalLineTo(11.2f); lineTo(12.8f, 20.5f)
                lineTo(20.5f, 12.8f); lineTo(11.2f, 3.5f); horizontalLineTo(4.5f)
                close()
                circle(8f, 8f, 1.3f)
            }
        }
    }

    val Wifi: ImageVector by lazy {
        tazIcon("Wifi") {
            outline(1.8f) {
                moveTo(3f, 9.5f); curveTo(8f, 4.8f, 16f, 4.8f, 21f, 9.5f)
                moveTo(6f, 12.8f); curveTo(9.4f, 9.8f, 14.6f, 9.8f, 18f, 12.8f)
                moveTo(9f, 16f); curveTo(10.7f, 14.6f, 13.3f, 14.6f, 15f, 16f)
                moveTo(12f, 19.2f); lineTo(12.01f, 19.2f)
            }
        }
    }

    val Bank: ImageVector by lazy {
        tazIcon("Bank") {
            outline(1.8f) {
                moveTo(3.5f, 9f); lineTo(12f, 4f); lineTo(20.5f, 9f); close()
                moveTo(6f, 11.5f); verticalLineTo(17f)
                moveTo(10f, 11.5f); verticalLineTo(17f)
                moveTo(14f, 11.5f); verticalLineTo(17f)
                moveTo(18f, 11.5f); verticalLineTo(17f)
                moveTo(3.5f, 20f); horizontalLineTo(20.5f)
            }
        }
    }

    val Card: ImageVector by lazy {
        tazIcon("Card") {
            outline(1.8f) {
                moveTo(3.5f, 6f); horizontalLineTo(20.5f); verticalLineTo(18f); horizontalLineTo(3.5f); close()
                moveTo(3.5f, 10f); horizontalLineTo(20.5f)
                moveTo(7f, 14.5f); horizontalLineTo(10f)
            }
        }
    }

    val IdCard: ImageVector by lazy {
        tazIcon("IdCard") {
            outline(1.8f) {
                moveTo(3.5f, 5.5f); horizontalLineTo(20.5f); verticalLineTo(18.5f); horizontalLineTo(3.5f); close()
                circle(9f, 10.5f, 2f)
                moveTo(6f, 15.5f); curveTo(6.8f, 13.9f, 11.2f, 13.9f, 12f, 15.5f)
                moveTo(14.5f, 10f); horizontalLineTo(18f)
                moveTo(14.5f, 13.5f); horizontalLineTo(17f)
            }
        }
    }

    val License: ImageVector by lazy {
        tazIcon("License") {
            outline(1.8f) {
                moveTo(5f, 3.5f); horizontalLineTo(19f); verticalLineTo(14f)
                moveTo(5f, 3.5f); verticalLineTo(20.5f); horizontalLineTo(11f)
                moveTo(8.5f, 8f); horizontalLineTo(15.5f)
                moveTo(8.5f, 11.5f); horizontalLineTo(12.5f)
                circle(16f, 17f, 2.3f)
                moveTo(14.6f, 18.9f); lineTo(14f, 21.5f); lineTo(16f, 20.5f); lineTo(18f, 21.5f); lineTo(17.4f, 18.9f)
            }
        }
    }

    val Category: ImageVector by lazy {
        tazIcon("Category") {
            outline(1.8f) {
                moveTo(4f, 4f); horizontalLineTo(10f); verticalLineTo(10f); horizontalLineTo(4f); close()
                moveTo(14f, 4f); horizontalLineTo(20f); verticalLineTo(10f); horizontalLineTo(14f); close()
                moveTo(4f, 14f); horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(4f); close()
                circle(17f, 17f, 3f)
            }
        }
    }

    val Copy: ImageVector by lazy {
        tazIcon("Copy") {
            outline(1.8f) {
                moveTo(9f, 8.5f); horizontalLineTo(19.5f); verticalLineTo(20.5f); horizontalLineTo(9f); close()
                moveTo(15f, 8.5f); verticalLineTo(4.5f); horizontalLineTo(4.5f); verticalLineTo(16f); horizontalLineTo(9f)
            }
        }
    }

    val Edit: ImageVector by lazy {
        tazIcon("Edit") {
            outline(1.8f) {
                moveTo(4.5f, 19.5f); lineTo(5.2f, 15.6f); lineTo(15.8f, 5f); lineTo(19f, 8.2f)
                lineTo(8.4f, 18.8f); close()
                moveTo(13.5f, 7.3f); lineTo(16.7f, 10.5f)
            }
        }
    }

    val Restore: ImageVector by lazy {
        tazIcon("Restore") {
            outline(1.8f) {
                moveTo(4.5f, 12f); curveTo(4.5f, 7.9f, 7.9f, 4.5f, 12f, 4.5f)
                curveTo(16.1f, 4.5f, 19.5f, 7.9f, 19.5f, 12f)
                curveTo(19.5f, 16.1f, 16.1f, 19.5f, 12f, 19.5f)
                curveTo(9.6f, 19.5f, 7.5f, 18.4f, 6.1f, 16.7f)
                moveTo(2.5f, 9.8f); lineTo(4.5f, 12.3f); lineTo(7f, 10.2f)
                moveTo(12f, 8f); verticalLineTo(12f); lineTo(14.8f, 13.8f)
            }
        }
    }

    val ZoomIn: ImageVector by lazy {
        tazIcon("ZoomIn") {
            outline(1.8f) {
                circle(10.5f, 10.5f, 6f)
                moveTo(15f, 15f); lineTo(20f, 20f)
                moveTo(10.5f, 8f); verticalLineTo(13f)
                moveTo(8f, 10.5f); horizontalLineTo(13f)
            }
        }
    }

    val ZoomOut: ImageVector by lazy {
        tazIcon("ZoomOut") {
            outline(1.8f) {
                circle(10.5f, 10.5f, 6f)
                moveTo(15f, 15f); lineTo(20f, 20f)
                moveTo(8f, 10.5f); horizontalLineTo(13f)
            }
        }
    }

    /** Fit to screen: four corner brackets. */
    val FitScreen: ImageVector by lazy {
        tazIcon("FitScreen") {
            outline(1.8f) {
                moveTo(4f, 9f); verticalLineTo(4f); horizontalLineTo(9f)
                moveTo(15f, 4f); horizontalLineTo(20f); verticalLineTo(9f)
                moveTo(20f, 15f); verticalLineTo(20f); horizontalLineTo(15f)
                moveTo(9f, 20f); horizontalLineTo(4f); verticalLineTo(15f)
            }
        }
    }

    /** Actual size: a 1:1 mark in a frame. */
    val ActualSize: ImageVector by lazy {
        tazIcon("ActualSize") {
            outline(1.8f) {
                moveTo(3.5f, 5f); horizontalLineTo(20.5f); verticalLineTo(19f); horizontalLineTo(3.5f); close()
                moveTo(7.5f, 10f); lineTo(9f, 9f); verticalLineTo(15f)
                moveTo(15f, 10f); lineTo(16.5f, 9f); verticalLineTo(15f)
                moveTo(12f, 10.5f); lineTo(12.01f, 10.5f)
                moveTo(12f, 13.5f); lineTo(12.01f, 13.5f)
            }
        }
    }

    val Calendar: ImageVector by lazy {
        tazIcon("Calendar") {
            outline(1.8f) {
                moveTo(4f, 6f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(4f); close()
                moveTo(4f, 10f); horizontalLineTo(20f)
                moveTo(8f, 3.5f); verticalLineTo(7.5f)
                moveTo(16f, 3.5f); verticalLineTo(7.5f)
            }
        }
    }

    val Phone: ImageVector by lazy {
        tazIcon("Phone") {
            outline(1.8f) {
                moveTo(7.5f, 3f); horizontalLineTo(16.5f); verticalLineTo(21f); horizontalLineTo(7.5f); close()
                moveTo(11f, 18f); horizontalLineTo(13f)
            }
        }
    }

    val Mail: ImageVector by lazy {
        tazIcon("Mail") {
            outline(1.8f) {
                moveTo(3.5f, 6f); horizontalLineTo(20.5f); verticalLineTo(18f); horizontalLineTo(3.5f); close()
                moveTo(3.5f, 6.5f); lineTo(12f, 13f); lineTo(20.5f, 6.5f)
            }
        }
    }

    val Globe: ImageVector by lazy {
        tazIcon("Globe") {
            outline(1.8f) {
                circle(12f, 12f, 8.5f)
                moveTo(3.5f, 12f); horizontalLineTo(20.5f)
                moveTo(12f, 3.5f); curveTo(14.4f, 6f, 15.5f, 9f, 15.5f, 12f)
                curveTo(15.5f, 15f, 14.4f, 18f, 12f, 20.5f)
                curveTo(9.6f, 18f, 8.5f, 15f, 8.5f, 12f)
                curveTo(8.5f, 9f, 9.6f, 6f, 12f, 3.5f)
            }
        }
    }

    val Hash: ImageVector by lazy {
        tazIcon("Hash") {
            outline(1.8f) {
                moveTo(9.5f, 4f); lineTo(7.5f, 20f)
                moveTo(16.5f, 4f); lineTo(14.5f, 20f)
                moveTo(4.5f, 9f); horizontalLineTo(19.5f)
                moveTo(4f, 15f); horizontalLineTo(19f)
            }
        }
    }

    val TextLines: ImageVector by lazy {
        tazIcon("TextLines") {
            outline(1.8f) {
                moveTo(4.5f, 6.5f); horizontalLineTo(19.5f)
                moveTo(4.5f, 11f); horizontalLineTo(19.5f)
                moveTo(4.5f, 15.5f); horizontalLineTo(15f)
                moveTo(4.5f, 20f); horizontalLineTo(11f)
            }
        }
    }

    /** Word wrap: a line that bends back under itself. */
    val Wrap: ImageVector by lazy {
        tazIcon("Wrap") {
            outline(1.8f) {
                moveTo(4.5f, 6.5f); horizontalLineTo(19.5f)
                moveTo(4.5f, 12f); horizontalLineTo(16.5f)
                curveTo(18.2f, 12f, 19.5f, 13.3f, 19.5f, 15f)
                curveTo(19.5f, 16.7f, 18.2f, 18f, 16.5f, 18f)
                horizontalLineTo(12.5f)
                moveTo(14.5f, 16f); lineTo(12.5f, 18f); lineTo(14.5f, 20f)
                moveTo(4.5f, 18f); horizontalLineTo(8.5f)
            }
        }
    }

    val Toggle: ImageVector by lazy {
        tazIcon("Toggle") {
            outline(1.8f) {
                pill(3.5f, 7f, 20.5f, 17f)
                circle(15.5f, 12f, 2.6f)
            }
        }
    }

    val ArrowUp: ImageVector by lazy {
        tazIcon("ArrowUp") {
            outline(1.8f) {
                moveTo(12f, 19f); verticalLineTo(5f)
                moveTo(6.5f, 10.5f); lineTo(12f, 5f); lineTo(17.5f, 10.5f)
            }
        }
    }

    val ArrowDown: ImageVector by lazy {
        tazIcon("ArrowDown") {
            outline(1.8f) {
                moveTo(12f, 5f); verticalLineTo(19f)
                moveTo(6.5f, 13.5f); lineTo(12f, 19f); lineTo(17.5f, 13.5f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        tazIcon("ChevronDown") {
            outline(1.8f) {
                moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f)
            }
        }
    }

    val ChevronUp: ImageVector by lazy {
        tazIcon("ChevronUp") {
            outline(1.8f) {
                moveTo(6f, 14.5f); lineTo(12f, 8.5f); lineTo(18f, 14.5f)
            }
        }
    }

    val Filter: ImageVector by lazy {
        tazIcon("Filter") {
            outline(1.8f) {
                moveTo(4f, 6f); horizontalLineTo(20f)
                moveTo(7f, 12f); horizontalLineTo(17f)
                moveTo(10f, 18f); horizontalLineTo(14f)
            }
        }
    }

    val Share: ImageVector by lazy {
        tazIcon("Share") {
            outline(1.8f) {
                circle(17.5f, 5.5f, 2.3f)
                circle(6.5f, 12f, 2.3f)
                circle(17.5f, 18.5f, 2.3f)
                moveTo(8.5f, 10.8f); lineTo(15.5f, 6.7f)
                moveTo(8.5f, 13.2f); lineTo(15.5f, 17.3f)
            }
        }
    }

    val OpenExternal: ImageVector by lazy {
        tazIcon("OpenExternal") {
            outline(1.8f) {
                moveTo(10.5f, 5f); horizontalLineTo(5f); verticalLineTo(19f); horizontalLineTo(19f); verticalLineTo(13.5f)
                moveTo(14f, 4.5f); horizontalLineTo(19.5f); verticalLineTo(10f)
                moveTo(19.5f, 4.5f); lineTo(11.5f, 12.5f)
            }
        }
    }

    val Page: ImageVector by lazy {
        tazIcon("Page") {
            outline(1.8f) {
                moveTo(6f, 3.5f); horizontalLineTo(18f); verticalLineTo(20.5f); horizontalLineTo(6f); close()
                moveTo(9f, 8f); horizontalLineTo(15f)
            }
        }
    }

    val Generate: ImageVector by lazy {
        tazIcon("Generate") {
            outline(1.8f) {
                moveTo(4.5f, 19.5f); lineTo(15f, 9f)
                moveTo(13f, 7f); lineTo(17f, 11f)
                moveTo(18f, 3.5f); verticalLineTo(6.5f)
                moveTo(16.5f, 5f); horizontalLineTo(19.5f)
                moveTo(9f, 3.5f); verticalLineTo(5.5f)
                moveTo(8f, 4.5f); horizontalLineTo(10f)
                moveTo(19.5f, 13f); verticalLineTo(15f)
                moveTo(18.5f, 14f); horizontalLineTo(20.5f)
            }
        }
    }

    val Info: ImageVector by lazy {
        tazIcon("Info") {
            outline(1.8f) {
                circle(12f, 12f, 8.5f)
                moveTo(12f, 11f); verticalLineTo(16.5f)
                moveTo(12f, 7.6f); lineTo(12.01f, 7.6f)
            }
        }
    }

    val Backup: ImageVector by lazy {
        tazIcon("Backup") {
            outline(1.8f) {
                // An archive box (backups are files on the device, never a cloud).
                moveTo(3.5f, 4.5f); horizontalLineTo(20.5f); verticalLineTo(8.5f); horizontalLineTo(3.5f); close()
                moveTo(5f, 8.5f); verticalLineTo(19.5f); horizontalLineTo(19f); verticalLineTo(8.5f)
                moveTo(12f, 11f); verticalLineTo(16.5f)
                moveTo(9.5f, 14f); lineTo(12f, 16.5f); lineTo(14.5f, 14f)
            }
        }
    }
}

private fun tazIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
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
