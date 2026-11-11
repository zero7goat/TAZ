package com.zero7goat.taz.features.files.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import com.zero7goat.taz.core.ui.AppFonts
import com.zero7goat.taz.core.ui.TazIcons
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.features.files.data.FileEntity
import com.zero7goat.taz.features.files.domain.PreviewKind
import com.zero7goat.taz.features.files.domain.previewKind
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The state of the inline preview of one file. */
sealed interface PreviewState {
    data object Loading : PreviewState

    /** This type has no inline preview; the screen offers details and Open instead. */
    data object None : PreviewState

    /** The stored copy is missing, or its recorded path is not a safe one. */
    data object Missing : PreviewState

    /** A previewable type that could not be read (corrupt, protected, too large to decode). */
    data object Failed : PreviewState

    data class Image(val bitmap: ImageBitmap) : PreviewState
    data class TextContent(val lines: List<String>, val isTruncated: Boolean) : PreviewState
    data class Pdf(val document: PdfPreviewDocument) : PreviewState
}

private const val MAX_IMAGE_DIMENSION = 2048
private const val MAX_TEXT_BYTES = 128 * 1024
private const val MAX_TEXT_LINES = 4000

/** Loads the preview on a background thread and releases PDF resources when it leaves the screen. */
@Composable
fun rememberPreviewState(file: FileEntity, resolveBlob: (FileEntity) -> File?): State<PreviewState> =
    produceState<PreviewState>(
        PreviewState.Loading,
        file.id, file.storedPath, file.mimeType, file.displayName
    ) {
        val kind = previewKind(file.mimeType, file.displayName)
        val loaded = withContext(Dispatchers.IO) { loadPreview(resolveBlob(file), kind) }
        value = loaded
        awaitDispose {
            if (loaded is PreviewState.Pdf) {
                CoroutineScope(Dispatchers.IO).launch { loaded.document.close() }
            }
        }
    }

private suspend fun loadPreview(blob: File?, kind: PreviewKind): PreviewState {
    if (blob == null || !blob.isFile) return PreviewState.Missing
    return when (kind) {
        PreviewKind.NONE -> PreviewState.None
        PreviewKind.IMAGE -> decodeImage(blob)?.let { PreviewState.Image(it.asImageBitmap()) } ?: PreviewState.Failed
        PreviewKind.TEXT -> readText(blob)
        PreviewKind.PDF -> {
            val document = PdfPreviewDocument.open(blob)
            if (document == null || document.pageCount == 0) {
                document?.close()
                PreviewState.Failed
            } else {
                try {
                    currentCoroutineContext().ensureActive()
                } catch (e: CancellationException) {
                    document.close()
                    throw e
                }
                PreviewState.Pdf(document)
            }
        }
    }
}

/** A decoded image and the size of the original file in pixels (after EXIF rotation). */
class DecodedImage(val bitmap: ImageBitmap, val originalWidth: Int, val originalHeight: Int)

/**
 * Decodes an image for the full-screen viewer: downsampled so its longer side is at most
 * [maxDimension] pixels, plus the original pixel size (used for "actual size"). Null if it cannot
 * be decoded. Runs on the calling thread; call it off the main thread.
 */
fun decodeImageForViewer(file: File, maxDimension: Int = MAX_IMAGE_DIMENSION): DecodedImage? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        BitmapFactory.decodeFile(file.path, bounds)
    } catch (e: Exception) {
        return null
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val bitmap = decodeImage(file, maxDimension) ?: return null
    val rotated = exifRotation(file) % 180 != 0
    return DecodedImage(
        bitmap = bitmap.asImageBitmap(),
        originalWidth = if (rotated) bounds.outHeight else bounds.outWidth,
        originalHeight = if (rotated) bounds.outWidth else bounds.outHeight
    )
}

/** Decodes a downsampled copy so large photos cannot exhaust memory. Returns null if it cannot be decoded. */
private fun decodeImage(file: File, maxDimension: Int = MAX_IMAGE_DIMENSION): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDimension ||
            bounds.outHeight / (sample * 2) >= maxDimension
        ) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        val rotation = exifRotation(file)
        if (rotation == 0) {
            bitmap
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

private fun exifRotation(file: File): Int =
    try {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

/** Reads at most the first 128 KB as UTF-8 text, never the whole file. */
private fun readText(file: File): PreviewState =
    try {
        val buffer = ByteArray(MAX_TEXT_BYTES)
        var read = 0
        var moreData = false
        FileInputStream(file).use { input ->
            while (read < MAX_TEXT_BYTES) {
                val count = input.read(buffer, read, MAX_TEXT_BYTES - read)
                if (count < 0) break
                read += count
            }
            moreData = read == MAX_TEXT_BYTES && input.read() >= 0
        }
        val allLines = String(buffer, 0, read, Charsets.UTF_8).lines()
        PreviewState.TextContent(
            lines = allLines.take(MAX_TEXT_LINES),
            isTruncated = moreData || allLines.size > MAX_TEXT_LINES
        )
    } catch (e: Exception) {
        PreviewState.Failed
    }

/**
 * A PDF opened for rendering with the platform PdfRenderer. The renderer allows one page at a
 * time, so rendering and closing are serialized.
 */
class PdfPreviewDocument private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) {
    val pageCount: Int = renderer.pageCount

    private val lock = Any()
    private var closed = false
    private val aspects = HashMap<Int, Float>()

    /** Height divided by width of page [index] (1.414 for A4 portrait); 1.414 if it cannot be read. */
    suspend fun pageAspect(index: Int): Float = withContext(Dispatchers.IO) {
        synchronized(lock) {
            aspects[index]?.let { return@synchronized it }
            if (closed || index !in 0 until pageCount) return@synchronized DEFAULT_ASPECT
            val aspect = try {
                val page = renderer.openPage(index)
                try {
                    if (page.width > 0) page.height.toFloat() / page.width else DEFAULT_ASPECT
                } finally {
                    page.close()
                }
            } catch (e: Exception) {
                DEFAULT_ASPECT
            }
            aspects[index] = aspect
            aspect
        }
    }

    suspend fun renderPage(index: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed || index !in 0 until pageCount) return@synchronized null
            try {
                val page = renderer.openPage(index)
                try {
                    val width = targetWidthPx.coerceIn(200, 2400)
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, 6000)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } finally {
                    page.close()
                }
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                renderer.close()
            } catch (e: Exception) {
                // Already closed or invalid; nothing else to release.
            }
            try {
                descriptor.close()
            } catch (e: Exception) {
                // Same.
            }
        }
    }

    companion object {
        private const val DEFAULT_ASPECT = 1.414f

        /** Returns null if the file is not a readable PDF (corrupt, or password protected). */
        fun open(file: File): PdfPreviewDocument? {
            var descriptor: ParcelFileDescriptor? = null
            return try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfPreviewDocument(descriptor, PdfRenderer(descriptor))
            } catch (e: Exception) {
                try {
                    descriptor?.close()
                } catch (closeError: Exception) {
                    // Ignore.
                }
                null
            }
        }
    }
}

/** Lines of a text file shown inline on the detail screen; the viewer shows the rest. */
private const val INLINE_TEXT_LINES = 40

/**
 * Adds the inline preview as items of a LazyColumn: the image, the first page of a PDF, or the first
 * lines of a text file, each with a way into the full-screen viewer ([onOpenViewer]).
 */
fun LazyListScope.previewItems(preview: PreviewState, fileName: String, pageWidthPx: Int, onOpenViewer: () -> Unit) {
    when (preview) {
        PreviewState.Loading -> item(key = "preview-loading") {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        PreviewState.None -> item(key = "preview-none") {
            PreviewNotice("There is no preview for this type of file. Use Open to view it in another app.")
        }

        PreviewState.Missing -> item(key = "preview-missing") {
            PreviewNotice("The stored copy of this file is missing, so it can't be previewed, opened or shared. You can delete the entry.")
        }

        PreviewState.Failed -> item(key = "preview-failed") {
            PreviewNotice("This file couldn't be previewed. It may be damaged, protected or too large. Try Open.")
        }

        is PreviewState.Image -> {
            item(key = "preview-image") {
                Image(
                    bitmap = preview.bitmap,
                    contentDescription = fileName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "View full screen", onClick = onOpenViewer),
                    contentScale = ContentScale.Fit
                )
            }
            item(key = "preview-open") {
                ViewerHint(TazIcons.ZoomIn, "View full screen and zoom", onOpenViewer, Modifier.padding(top = 12.dp))
            }
        }

        is PreviewState.TextContent -> {
            item(key = "preview-text") {
                Surface(
                    onClick = onOpenViewer,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        preview.lines.take(INLINE_TEXT_LINES).forEach { line ->
                            Text(
                                text = line.ifEmpty { " " },
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppFonts.monospace)
                            )
                        }
                    }
                }
            }
            item(key = "preview-open") {
                ViewerHint(
                    TazIcons.TextLines,
                    if (preview.lines.size > INLINE_TEXT_LINES || preview.isTruncated) "View the whole file" else "Open in text viewer",
                    onOpenViewer,
                    Modifier.padding(top = 12.dp)
                )
            }
        }

        is PreviewState.Pdf -> {
            item(key = "page-0") {
                Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open PDF viewer", onClick = onOpenViewer)) {
                    PdfPage(preview.document, 0, pageWidthPx)
                }
            }
            item(key = "preview-open") {
                val pages = preview.document.pageCount
                ViewerHint(
                    TazIcons.Page,
                    if (pages == 1) "Open PDF viewer · 1 page" else "Open PDF viewer · $pages pages",
                    onOpenViewer,
                    Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PreviewNotice(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun PdfPage(document: PdfPreviewDocument, index: Int, widthPx: Int) {
    val page by produceState<ImageBitmap?>(null, document, index) {
        value = document.renderPage(index, widthPx)?.asImageBitmap()
    }
    val bitmap = page
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Page ${index + 1}",
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentScale = ContentScale.FillWidth
        )
    } else {
        Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
