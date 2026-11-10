package com.zero7goat.taz.features.notes.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.features.notes.data.NoteImageStore
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of copying a picked image into the note image store. */
sealed interface NoteImageImport {
    data class Imported(val name: String) : NoteImageImport
    data object TooLarge : NoteImageImport
    data object Unsupported : NoteImageImport
    data object Failed : NoteImageImport
}

/** Copies the picked image at [uri] into [store] on a background thread. The source is only read. */
internal suspend fun importNoteImage(context: Context, store: NoteImageStore, uri: Uri): NoteImageImport =
    withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        val mimeType = try {
            resolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        val extension = NoteImageStore.extensionForMime(mimeType)
            // HEIC can only be decoded from Android 9 on.
            ?.takeUnless { it == "heic" && Build.VERSION.SDK_INT < 28 }
            ?: return@withContext NoteImageImport.Unsupported
        try {
            val input = resolver.openInputStream(uri) ?: return@withContext NoteImageImport.Failed
            NoteImageImport.Imported(input.use { store.importStream(it, extension) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoteImageStore.ImageTooLargeException) {
            NoteImageImport.TooLarge
        } catch (e: Exception) {
            NoteImageImport.Failed
        }
    }

private sealed interface ImageState {
    data object Loading : ImageState
    data object Missing : ImageState
    data class Ready(val bitmap: ImageBitmap) : ImageState
}

/** Shows a stored note image, decoded off the main thread and scaled down to a sensible size. */
@Composable
internal fun NoteImage(name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by produceState<ImageState>(ImageState.Loading, name) {
        value = withContext(Dispatchers.IO) {
            val file = NoteImageStore(context.applicationContext.filesDir).resolve(name)
            file?.let { decodeScaled(it) }?.let { ImageState.Ready(it.asImageBitmap()) } ?: ImageState.Missing
        }
    }
    val shape = RoundedCornerShape(8.dp)
    when (val current = state) {
        is ImageState.Ready -> Image(
            bitmap = current.bitmap,
            contentDescription = "Image in note",
            modifier = modifier
                .fillMaxWidth()
                .clip(shape),
            contentScale = ContentScale.FillWidth
        )
        else -> Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (current == ImageState.Missing) {
                Text(
                    text = "Image not available",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private const val MAX_IMAGE_DIMENSION = 1600

/** Decodes [file] so neither side exceeds about [MAX_IMAGE_DIMENSION] pixels, or returns null. */
private fun decodeScaled(file: File): Bitmap? {
    if (!file.isFile) return null
    return try {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also applies the photo's EXIF rotation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            BitmapFactory.decodeFile(file.path, options)
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

private fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= MAX_IMAGE_DIMENSION || height / (sample * 2) >= MAX_IMAGE_DIMENSION) {
        sample *= 2
    }
    return sample
}
