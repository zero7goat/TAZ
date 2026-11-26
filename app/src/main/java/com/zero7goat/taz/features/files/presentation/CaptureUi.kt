package com.zero7goat.taz.features.files.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zero7goat.taz.core.ui.TazIcons
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What can be added to Files. */
enum class AddKind { IMPORT, PHOTO, VIDEO, SCAN, VOICE }

/** The "+" sheet of Files: import from the phone, or capture straight into the vault. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFileSheet(onPick: (AddKind) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Captured photos, videos, scans and recordings go straight into TAZ, encrypted. They never appear in your gallery.",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            SheetRow(TazIcons.Import, "Import from phone") { onPick(AddKind.IMPORT) }
            SheetRow(TazIcons.Camera, "Take a photo") { onPick(AddKind.PHOTO) }
            SheetRow(TazIcons.Camera, "Record a video") { onPick(AddKind.VIDEO) }
            SheetRow(TazIcons.Page, "Scan a document (PDF)") { onPick(AddKind.SCAN) }
            SheetRow(TazIcons.Phone, "Record a voice note") { onPick(AddKind.VOICE) }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** A private, temporary folder for captures; each file is moved into the vault and deleted. */
fun captureFile(context: Context, prefix: String, extension: String): File {
    val dir = File(context.cacheDir, "capture").also { it.mkdirs() }
    val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
    return File(dir, "$prefix $stamp.$extension")
}

fun clearCaptures(context: Context) {
    File(context.cacheDir, "capture").deleteRecursively()
}

/** Asks for the permissions [kind] needs, then calls [onReady]. */
@Composable
fun rememberCapturePermissions(onDenied: (String) -> Unit, onReady: (AddKind) -> Unit): (AddKind) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<AddKind?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val kind = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val needed = permissionsFor(kind)
        // Video still works without the microphone (silent); everything else needs its permission.
        val ok = if (kind == AddKind.VIDEO) result[Manifest.permission.CAMERA] == true else needed.all { result[it] == true }
        if (ok) onReady(kind) else onDenied(if (kind == AddKind.VOICE) "Microphone access is needed to record." else "Camera access is needed for this.")
    }
    return { kind ->
        val needed = permissionsFor(kind)
        if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            onReady(kind)
        } else {
            pending = kind
            launcher.launch(needed.toTypedArray())
        }
    }
}

private fun permissionsFor(kind: AddKind): List<String> = when (kind) {
    AddKind.PHOTO, AddKind.SCAN -> listOf(Manifest.permission.CAMERA)
    AddKind.VIDEO -> listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    AddKind.VOICE -> listOf(Manifest.permission.RECORD_AUDIO)
    AddKind.IMPORT -> emptyList()
}

// ---------- camera ----------

@Composable
private fun FullScreen(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
    }
}

/** Binds the back camera to [previewView] with [useCases] while shown. */
@Composable
private fun BindCamera(previewView: PreviewView, vararg useCases: androidx.camera.core.UseCase) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = try { future.get() } catch (e: Exception) { return@addListener }
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, *useCases)
            } catch (e: Exception) {
                // No usable camera.
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, recording: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .background(if (recording) Color(0xFFE53935) else Color.White.copy(alpha = if (enabled) 1f else 0.4f), if (recording) RoundedCornerShape(8.dp) else CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    )
}

/** Takes one photo into a private temporary file. */
@Composable
fun PhotoCaptureDialog(onCaptured: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var busy by remember { mutableStateOf(false) }
    FullScreen(onDismiss) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        BindCamera(previewView, capture)
        CloseButton(onDismiss)
        Box(Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(28.dp)) {
            ShutterButton(enabled = !busy) {
                busy = true
                val file = captureFile(context, "Photo", "jpg")
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) = onCaptured(file)
                        override fun onError(exception: ImageCaptureException) {
                            busy = false
                            file.delete()
                        }
                    }
                )
            }
        }
    }
}

/** Records a video (with sound when the microphone is allowed) into a private temporary file. */
@SuppressLint("MissingPermission")
@Composable
fun VideoCaptureDialog(onCaptured: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val recorder = remember { Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build() }
    val video = remember { VideoCapture.withOutput(recorder) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(recording) {
        seconds = 0
        while (recording != null) { delay(1000); seconds++ }
    }
    DisposableEffect(Unit) { onDispose { recording?.stop() } }
    FullScreen({ recording?.stop(); onDismiss() }) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        BindCamera(previewView, video)
        CloseButton { recording?.stop(); onDismiss() }
        Column(Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (recording != null) Text(formatSeconds(seconds), color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(12.dp))
            ShutterButton(enabled = true, recording = recording != null) {
                val current = recording
                if (current != null) {
                    current.stop()
                    recording = null
                } else {
                    val file = captureFile(context, "Video", "mp4")
                    val micAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val pending = recorder.prepareRecording(context, FileOutputOptions.Builder(file).build())
                        .let { if (micAllowed) it.withAudioEnabled() else it }
                    recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) file.delete() else onCaptured(file)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Scans pages with the camera into one PDF. Each page is cropped to the frame on screen and
 * turned into a clean, high-contrast greyscale image, like a photocopy.
 */
@Composable
fun DocumentScanDialog(onCaptured: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val capture = remember { ImageCapture.Builder().build() }
    val pages = remember { mutableStateListOf<Bitmap>() }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { pages.forEach { it.recycle() } } }
    FullScreen(onDismiss) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        BindCamera(previewView, capture)
        // The page guide: what is inside it becomes the page.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.82f)
                .aspectRatio(1f / 1.414f)
                .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
        )
        CloseButton(onDismiss)
        Column(Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (pages.isEmpty()) "Fit the page inside the frame" else "${pages.size} ${if (pages.size == 1) "page" else "pages"} scanned",
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Spacer(Modifier.size(88.dp))
                ShutterButton(enabled = !busy) {
                    busy = true
                    capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            scope.launch {
                                val page = withContext(Dispatchers.Default) { runCatching { toScannedPage(image) }.getOrNull() }
                                image.close()
                                if (page != null) pages += page
                                busy = false
                            }
                        }
                        override fun onError(exception: ImageCaptureException) { busy = false }
                    })
                }
                Button(enabled = pages.isNotEmpty() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        val pdf = withContext(Dispatchers.IO) { writePdf(context, pages) }
                        onCaptured(pdf)
                    }
                }, modifier = Modifier.size(width = 88.dp, height = 44.dp)) { Text("Done") }
            }
        }
    }
}

private fun toScannedPage(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Unreadable")
    val rotation = image.imageInfo.rotationDegrees
    val upright = if (rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    if (upright !== raw) raw.recycle()
    // Same proportions as the on-screen guide: 82% of the width, A4 shape, centred.
    val w = (upright.width * 0.82f).toInt()
    val h = minOf((w * 1.414f).toInt(), upright.height)
    val cropped = Bitmap.createBitmap(upright, (upright.width - w) / 2, (upright.height - h) / 2, w, h)
    if (cropped !== upright) upright.recycle()
    // Greyscale with extra contrast: reads like a photocopy and keeps the PDF small.
    val out = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
    val contrast = 1.6f
    val shift = (-0.5f * contrast + 0.5f) * 255f + 20f
    val matrix = ColorMatrix().apply { setSaturation(0f) }.also {
        it.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, shift,
            0f, contrast, 0f, 0f, shift,
            0f, 0f, contrast, 0f, shift,
            0f, 0f, 0f, 1f, 0f
        )))
    }
    Canvas(out).drawBitmap(cropped, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
    cropped.recycle()
    return out
}

private fun writePdf(context: Context, pages: List<Bitmap>): File {
    val file = captureFile(context, "Scan", "pdf")
    val document = PdfDocument()
    try {
        pages.forEachIndexed { index, bitmap ->
            // A4 at 72 points per inch.
            val info = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
            val page = document.startPage(info)
            val scale = minOf(595f / bitmap.width, 842f / bitmap.height)
            val dx = (595f - bitmap.width * scale) / 2f
            val dy = (842f - bitmap.height * scale) / 2f
            page.canvas.drawBitmap(bitmap, Matrix().apply { postScale(scale, scale); postTranslate(dx, dy) }, Paint(Paint.FILTER_BITMAP_FLAG))
            document.finishPage(page)
        }
        FileOutputStream(file).use { document.writeTo(it) }
    } finally {
        document.close()
    }
    return file
}

// ---------- voice ----------

/** Records a voice note (AAC in .m4a) into a private temporary file. */
@Composable
fun VoiceRecorderDialog(onCaptured: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(recorder) {
        seconds = 0
        while (recorder != null) { delay(1000); seconds++ }
    }
    fun stop(save: Boolean) {
        val r = recorder ?: return
        recorder = null
        val ok = try { r.stop(); true } catch (e: Exception) { false }
        r.release()
        val f = file
        if (save && ok && f != null) onCaptured(f) else f?.delete()
    }
    DisposableEffect(Unit) { onDispose { stop(save = false) } }

    AlertDialog(
        onDismissRequest = { stop(false); onDismiss() },
        title = { Text("Voice note") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(formatSeconds(seconds), style = MaterialTheme.typography.displaySmall)
                Text(
                    if (recorder != null) "Recording… The recording is saved in TAZ, encrypted." else "Tap Record to start.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (recorder == null) {
                TextButton(onClick = {
                    val target = captureFile(context, "Voice note", "m4a")
                    @Suppress("DEPRECATION")
                    val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                    try {
                        r.setAudioSource(MediaRecorder.AudioSource.MIC)
                        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        r.setAudioEncodingBitRate(96_000)
                        r.setAudioSamplingRate(44_100)
                        r.setOutputFile(target.absolutePath)
                        r.prepare()
                        r.start()
                        file = target
                        recorder = r
                        error = null
                    } catch (e: Exception) {
                        r.release()
                        target.delete()
                        error = "The microphone couldn't be used."
                    }
                }) { Text("Record") }
            } else {
                TextButton(onClick = { stop(save = true) }) { Text("Stop and save") }
            }
        },
        dismissButton = { TextButton(onClick = { stop(false); onDismiss() }) { Text("Cancel") } }
    )
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        IconButton(onClick = onClick, modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp)) {
            Icon(TazIcons.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}

private fun formatSeconds(total: Int) = "%d:%02d".format(total / 60, total % 60)
