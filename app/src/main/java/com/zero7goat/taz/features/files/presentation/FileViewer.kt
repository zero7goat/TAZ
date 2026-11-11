package com.zero7goat.taz.features.files.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zero7goat.taz.core.ui.AppFonts
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.files.data.FileEntity
import com.zero7goat.taz.features.files.domain.formatFileSize
import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** The toolbar every viewer shares: back, the file name, and share / open in another app. */
@Composable
private fun ViewerBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    background: Color,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(TazIcons.ChevronLeft, contentDescription = "Back") }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
        IconButton(onClick = onShare) { Icon(TazIcons.Share, contentDescription = "Share") }
        IconButton(onClick = onOpen) { Icon(TazIcons.OpenExternal, contentDescription = "Open in another app") }
    }
}

// ---------- images ----------

/**
 * Full-screen image viewer. Swipe between the images of the same folder; pinch or double-tap to
 * zoom, drag to pan while zoomed. Fit and actual size from the toolbar. Images are decoded one page
 * at a time at a capped resolution, so even very large photos cannot exhaust memory.
 */
@Composable
fun ImageViewer(
    images: List<FileEntity>,
    startId: String,
    resolveBlob: (FileEntity) -> File?,
    onClose: () -> Unit,
    onShare: (FileEntity) -> Unit,
    onOpen: (FileEntity) -> Unit
) {
    val startIndex = images.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startIndex) { images.size }
    // Zoom state per page lives here so the toolbar can drive it.
    val zoomStates = remember { HashMap<String, ZoomState>() }
    val current = images.getOrNull(pager.currentPage)
    val currentZoom = current?.let { zoomStates.getOrPut(it.id) { ZoomState() } }
    var chromeVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onClose)

    // A dark stage is the convention for photos in every theme; its controls use white.
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                key = { images[it].id },
                // Zoomed in, a drag pans the image instead of turning the page.
                userScrollEnabled = (currentZoom?.scale ?: 1f) <= 1.01f
            ) { page ->
                val file = images[page]
                val zoom = zoomStates.getOrPut(file.id) { ZoomState() }
                ZoomableImagePage(
                    file = file,
                    resolveBlob = resolveBlob,
                    zoom = zoom,
                    onTap = { chromeVisible = !chromeVisible }
                )
            }
            AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                if (current != null) {
                    ViewerBar(
                        title = current.displayName,
                        subtitle = if (images.size > 1) "${pager.currentPage + 1} of ${images.size}" else formatFileSize(current.sizeBytes),
                        onBack = onClose,
                        onShare = { onShare(current) },
                        onOpen = { onOpen(current) },
                        background = Color.Black.copy(alpha = 0.55f),
                        actions = {
                            val zoomed = (currentZoom?.scale ?: 1f) > 1.01f
                            IconButton(onClick = {
                                val z = currentZoom ?: return@IconButton
                                scope.launch { if (zoomed) z.animateTo(1f, Offset.Zero) else z.animateTo(z.actualSizeScale(), Offset.Zero) }
                            }) {
                                Icon(
                                    if (zoomed) TazIcons.FitScreen else TazIcons.ActualSize,
                                    contentDescription = if (zoomed) "Fit to screen" else "Actual size"
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

/** Zoom and pan of one image, plus what is needed to compute "actual size". */
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var container = IntSize.Zero
    var fitted = IntSize.Zero
    var originalWidth = 0

    /** The zoom at which one image pixel is one screen pixel. */
    fun actualSizeScale(): Float =
        if (fitted.width > 0 && originalWidth > 0) (originalWidth.toFloat() / fitted.width).coerceIn(1f, MAX_ZOOM * 2) else DOUBLE_TAP_ZOOM

    fun clamp(candidate: Offset, atScale: Float = scale): Offset {
        val maxX = max(0f, (fitted.width * atScale - container.width) / 2f)
        val maxY = max(0f, (fitted.height * atScale - container.height) / 2f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun snapTo(newScale: Float, newOffset: Offset) {
        scale = newScale
        offset = clamp(newOffset, newScale)
    }

    /** A short eased zoom (double-tap, fit, actual size). */
    suspend fun animateTo(newScale: Float, newOffset: Offset) {
        val target = clamp(newOffset, newScale)
        val start = offset
        val startScale = scale
        animate(0f, 1f, animationSpec = tween(220)) { progress, _ ->
            scale = startScale + (newScale - startScale) * progress
            offset = Offset(start.x + (target.x - start.x) * progress, start.y + (target.y - start.y) * progress)
        }
    }
}

@Composable
private fun ZoomableImagePage(file: FileEntity, resolveBlob: (FileEntity) -> File?, zoom: ZoomState, onTap: () -> Unit) {
    val loaded by produceState<ImageLoad>(ImageLoad.Loading, file.id, file.storedPath) {
        value = withContext(Dispatchers.IO) {
            val blob = resolveBlob(file)
            when {
                blob == null || !blob.isFile -> ImageLoad.Failed("The stored copy of this image is missing.")
                else -> decodeImageForViewer(blob)?.let { ImageLoad.Ready(it) }
                    ?: ImageLoad.Failed("This image couldn't be shown. It may be damaged or in an unsupported format.")
            }
        }
    }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val state = loaded) {
            ImageLoad.Loading -> CircularProgressIndicator(color = Color.White)
            is ImageLoad.Failed -> Text(
                state.message,
                modifier = Modifier.padding(32.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            is ImageLoad.Ready -> {
                val image = state.image
                val density = LocalDensity.current
                val containerW = with(density) { maxWidth.toPx() }
                val containerH = with(density) { maxHeight.toPx() }
                val aspect = image.bitmap.width.toFloat() / image.bitmap.height
                val fittedW = min(containerW, containerH * aspect)
                zoom.container = IntSize(containerW.roundToInt(), containerH.roundToInt())
                zoom.fitted = IntSize(fittedW.roundToInt(), (fittedW / aspect).roundToInt())
                zoom.originalWidth = image.originalWidth

                Image(
                    bitmap = image.bitmap,
                    contentDescription = file.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(file.id) {
                            detectTapGestures(
                                onTap = { onTap() },
                                onDoubleTap = { tap ->
                                    scope.launch {
                                        if (zoom.scale > 1.01f) {
                                            zoom.animateTo(1f, Offset.Zero)
                                        } else {
                                            // Zoom towards the tapped point.
                                            val center = Offset(size.width / 2f, size.height / 2f)
                                            zoom.animateTo(DOUBLE_TAP_ZOOM, (center - tap) * (DOUBLE_TAP_ZOOM - 1f))
                                        }
                                    }
                                }
                            )
                        }
                        .pointerInput(file.id) {
                            // Two fingers always zoom; one finger pans only while zoomed in, so the
                            // pager can still be swiped at normal size.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val fingers = event.changes.count { it.pressed }
                                    if (fingers >= 2 || zoom.scale > 1.01f) {
                                        val zoomChange = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val newScale = (zoom.scale * zoomChange).coerceIn(1f, max(MAX_ZOOM, zoom.actualSizeScale()))
                                        zoom.snapTo(newScale, zoom.offset * (newScale / zoom.scale) + pan)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .graphicsLayer {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y
                        }
                )
            }
        }
    }
}

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    class Ready(val image: DecodedImage) : ImageLoad
    class Failed(val message: String) : ImageLoad
}

// ---------- PDF ----------

/**
 * Full-screen PDF viewer: pages scroll vertically; pinch or double-tap to zoom (pages are rendered
 * again at the new size), fit to width or to page, a page counter, previous / next and go-to-page.
 * Only the pages on screen are rendered.
 */
@Composable
fun PdfViewer(
    file: FileEntity,
    document: PdfPreviewDocument,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }
    var fitPage by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    val pageCount = document.pageCount
    val currentPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            // The page covering the middle of the screen.
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.firstOrNull { it.offset <= middle && it.offset + it.size >= middle }?.index
                ?: listState.firstVisibleItemIndex
        }
    }
    val firstAspect by produceState(1.414f, document) { value = document.pageAspect(0) }

    BackHandler(onBack = onClose)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        ViewerBar(
            title = file.displayName,
            subtitle = "Page ${(currentPage + 1).coerceAtMost(pageCount)} of $pageCount",
            onBack = onClose,
            onShare = onShare,
            onOpen = onOpen,
            background = MaterialTheme.colorScheme.surface,
            actions = {
                IconButton(onClick = {
                    fitPage = !fitPage
                    zoom = 1f
                }) {
                    Icon(
                        if (fitPage) TazIcons.FitScreen else TazIcons.Page,
                        contentDescription = if (fitPage) "Fit to width" else "Fit whole page"
                    )
                }
            }
        )
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val viewportW = maxWidth
            val viewportH = maxHeight
            // Fit page: the page height matches the screen; fit width: the page width does.
            val baseWidth = if (fitPage) min(viewportW.value, (viewportH.value - 16f) / firstAspect).dp else viewportW
            val contentWidth = baseWidth * zoom
            val density = LocalDensity.current
            val renderWidthPx = with(density) { contentWidth.roundToPx() }.coerceAtMost(2048)
            // Rendering again for every pinch step would be wasteful: round to steps of 256 px.
            val renderBucket = ((renderWidthPx + 255) / 256) * 256

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState(), enabled = contentWidth > viewportW)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { zoom = if (zoom > 1.01f) 1f else 2f })
                    }
                    .pointerInput(Unit) {
                        // Two-finger pinch zooms; one finger is left to the scrolling list.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .requiredWidth(max(contentWidth.value, viewportW.value).dp)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(pageCount, key = { it }) { index ->
                        PdfViewerPage(document, index, contentWidth, renderBucket)
                    }
                }
            }

            // Page controls float at the bottom.
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { scope.launch { listState.animateScrollToItem((currentPage - 1).coerceAtLeast(0)) } },
                        enabled = currentPage > 0
                    ) { Icon(TazIcons.ChevronUp, contentDescription = "Previous page") }
                    TextButton(onClick = { goTo = true }) { Text("${(currentPage + 1).coerceAtMost(pageCount)} / $pageCount") }
                    IconButton(
                        onClick = { scope.launch { listState.animateScrollToItem((currentPage + 1).coerceAtMost(pageCount - 1)) } },
                        enabled = currentPage < pageCount - 1
                    ) { Icon(TazIcons.ChevronDown, contentDescription = "Next page") }
                    IconButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f) }, enabled = zoom > 1f) {
                        Icon(TazIcons.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(4f) }, enabled = zoom < 4f) {
                        Icon(TazIcons.ZoomIn, contentDescription = "Zoom in")
                    }
                }
            }
        }
    }

    if (goTo) {
        var text by remember { mutableStateOf("") }
        val number = text.toIntOrNull()
        val valid = number != null && number in 1..pageCount
        AlertDialog(
            onDismissRequest = { goTo = false },
            title = { Text("Go to page") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(6) },
                    label = { Text("Page (1 to $pageCount)") },
                    singleLine = true,
                    isError = text.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    goTo = false
                    scope.launch { listState.scrollToItem((number ?: 1) - 1) }
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { goTo = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PdfViewerPage(document: PdfPreviewDocument, index: Int, width: androidx.compose.ui.unit.Dp, renderWidthPx: Int) {
    val aspect by produceState(1.414f, document, index) { value = document.pageAspect(index) }
    // Keeps the previous rendering on screen while a sharper one is made after zooming.
    var bitmap by remember(document, index) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(document, index) { mutableStateOf(false) }
    LaunchedEffect(document, index, renderWidthPx) {
        val rendered = document.renderPage(index, renderWidthPx)
        if (rendered != null) bitmap = rendered.asImageBitmap() else if (bitmap == null) failed = true
    }
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(1f / aspect)
            .background(Color.White)
            .semantics { contentDescription = "Page ${index + 1}" },
        contentAlignment = Alignment.Center
    ) {
        val shown = bitmap
        when {
            shown != null -> Image(shown, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            failed -> Text("This page couldn't be shown.", color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
            else -> CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color.Gray)
        }
    }
}

// ---------- text ----------

/** A text file read for the viewer: at most [MAX_VIEWER_TEXT_BYTES]. */
class TextDocument(val lines: List<String>, val isTruncated: Boolean, val longestLine: Int)

private const val MAX_VIEWER_TEXT_BYTES = 1024 * 1024
private const val MAX_VIEWER_LINES = 20_000
private const val MAX_RENDERED_LINE = 4_000

/** Reads the start of a text file for the viewer; never more than 1 MB. Null if it cannot be read. */
fun readTextDocument(file: File): TextDocument? =
    try {
        val buffer = ByteArray(MAX_VIEWER_TEXT_BYTES)
        var read = 0
        var moreData: Boolean
        FileInputStream(file).use { input ->
            while (read < MAX_VIEWER_TEXT_BYTES) {
                val count = input.read(buffer, read, MAX_VIEWER_TEXT_BYTES - read)
                if (count < 0) break
                read += count
            }
            moreData = read == MAX_VIEWER_TEXT_BYTES && input.read() >= 0
        }
        // Tabs become four spaces so indentation lines up in the monospace grid.
        val all = String(buffer, 0, read, Charsets.UTF_8).replace("\r\n", "\n").replace('\t'.toString(), "    ").split('\n')
        val lines = all.take(MAX_VIEWER_LINES)
        TextDocument(lines, moreData || all.size > MAX_VIEWER_LINES, lines.maxOfOrNull { it.length } ?: 0)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

/**
 * Full-screen text and code viewer: monospace font, line numbers, indentation kept, and a toggle
 * between wrapping long lines and scrolling sideways.
 */
@Composable
fun TextViewer(
    file: FileEntity,
    resolveBlob: (FileEntity) -> File?,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit
) {
    val document by produceState<TextLoad>(TextLoad.Loading, file.id) {
        value = withContext(Dispatchers.IO) {
            val blob = resolveBlob(file)
            if (blob == null || !blob.isFile) TextLoad.Failed else readTextDocument(blob)?.let { TextLoad.Ready(it) } ?: TextLoad.Failed
        }
    }
    var wrap by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val ready = document as? TextLoad.Ready
        ViewerBar(
            title = file.displayName,
            subtitle = ready?.let {
                "${it.doc.lines.size} lines · ${formatFileSize(file.sizeBytes)} · UTF-8" + if (it.doc.isTruncated) " · beginning only" else ""
            },
            onBack = onClose,
            onShare = onShare,
            onOpen = onOpen,
            background = MaterialTheme.colorScheme.surface,
            actions = {
                IconButton(onClick = { wrap = !wrap }) {
                    Icon(
                        if (wrap) TazIcons.TextLines else TazIcons.Wrap,
                        contentDescription = if (wrap) "Don't wrap long lines" else "Wrap long lines"
                    )
                }
            }
        )
        when (val state = document) {
            TextLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            TextLoad.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "This file couldn't be read as text. It may be missing, damaged or in another encoding. Try Open.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is TextLoad.Ready -> CodeLines(state.doc, wrap)
        }
    }
}

private sealed interface TextLoad {
    data object Loading : TextLoad
    data object Failed : TextLoad
    class Ready(val doc: TextDocument) : TextLoad
}

@Composable
private fun CodeLines(doc: TextDocument, wrap: Boolean) {
    val style = TextStyle(
        fontFamily = AppFonts.monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
    val numberStyle = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontWeight = FontWeight.Normal)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(style) { measurer.measure("0", style).size.width }
    val gutterChars = doc.lines.size.toString().length
    val gutterWidth = with(density) { (charWidth * (gutterChars + 1)).toDp() } + 12.dp
    // Without wrapping, every line gets the width of the longest one (capped so huge lines stay fast).
    val contentWidth = with(density) { (charWidth * min(doc.longestLine, MAX_RENDERED_LINE)).toDp() } + gutterWidth + 32.dp

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = if (wrap) maxWidth else max(contentWidth.value, maxWidth.value).dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
        ) {
            LazyColumn(
                modifier = Modifier.requiredWidth(width).fillMaxHeight(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
            ) {
                items(doc.lines.size, key = { it }) { index ->
                    Row(modifier = Modifier.fillMaxWidth().padding(end = 16.dp)) {
                        Text(
                            text = (index + 1).toString(),
                            modifier = Modifier.width(gutterWidth).padding(end = 12.dp),
                            style = numberStyle,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                        val line = doc.lines[index]
                        Text(
                            text = if (line.length > MAX_RENDERED_LINE) line.take(MAX_RENDERED_LINE) + " …" else line.ifEmpty { " " },
                            style = style,
                            softWrap = wrap
                        )
                    }
                }
                if (doc.isTruncated) {
                    item {
                        Text(
                            "Only the first part of this file is shown. Use Open to see all of it.",
                            modifier = Modifier.padding(16.dp).widthIn(max = 360.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/** Used by the detail screen for its tappable "open the viewer" affordance. */
@Composable
fun ViewerHint(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(TazIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
