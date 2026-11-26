package com.zero7goat.taz.core.totp

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.zero7goat.taz.core.ui.TazIcons
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** QR decoding with ZXing, entirely on the device. */
object QrDecoder {

    private fun reader() = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    fun decode(source: LuminanceSource): String? {
        val reader = reader()
        for (candidate in listOf(source, source.invert())) {
            try {
                return reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate))).text
            } catch (e: NotFoundException) {
                // try the inverted image (light code on dark background)
            } catch (e: ReaderException) {
                // unreadable this time
            } finally {
                reader.reset()
            }
        }
        return null
    }

    /** Reads a QR code from a picture (for example a screenshot). Off the main thread. */
    fun decodeImage(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            decode(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * A full-screen camera view that reports the first QR code it reads. The camera runs only while
 * this is shown; frames stay in memory and are never saved. The caller must hold the camera
 * permission before showing it.
 */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPreview(onResult = onResult)
            // A frame to aim with.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(240.dp)
                    .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
            )
            Column(
                Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Point the camera at the QR code from the website's two-factor setup page.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp)
            ) { Icon(TazIcons.Close, contentDescription = "Close scanner", tint = Color.White) }
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val cameraProvider = try {
                future.get()
            } catch (e: Exception) {
                return@addListener
            }
            provider = cameraProvider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                val text = try {
                    if (done.get()) null else decodeFrame(image)
                } finally {
                    image.close()
                }
                if (text != null && done.compareAndSet(false, true)) {
                    ContextCompat.getMainExecutor(context).execute { onResult(text) }
                }
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                // No usable back camera; the user can close the scanner or pick a screenshot instead.
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun decodeFrame(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    val rowStride = plane.rowStride
    if (rowStride <= 0 || data.size < rowStride * (image.height - 1) + image.width) return null
    return QrDecoder.decode(
        PlanarYUVLuminanceSource(data, rowStride, image.height, 0, 0, image.width, image.height, false)
    )
}
