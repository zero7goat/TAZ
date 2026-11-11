package com.zero7goat.taz.features.files.presentation

import androidx.compose.ui.graphics.vector.ImageVector
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.files.domain.FileCategory
import com.zero7goat.taz.features.files.domain.PreviewKind
import com.zero7goat.taz.features.files.domain.fileCategory
import com.zero7goat.taz.features.files.domain.previewKind

/** The icon for a stored file: images, documents and text files are told apart at a glance. */
fun fileIconFor(mimeType: String, name: String): ImageVector = when {
    fileCategory(mimeType, name) == FileCategory.IMAGE -> TazIcons.Image
    previewKind(mimeType, name) == PreviewKind.TEXT -> TazIcons.TextLines
    fileCategory(mimeType, name) == FileCategory.DOCUMENT -> TazIcons.Page
    else -> TazIcons.File
}
