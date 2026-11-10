package com.zero7goat.taz.features.files.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.storage.extensionOf
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.files.data.FileEntity
import com.zero7goat.taz.features.files.domain.fileCategory
import com.zero7goat.taz.features.files.domain.formatFileSize
import com.zero7goat.taz.features.files.domain.keywordList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

private class FileLoad(val file: FileEntity?)

/**
 * Full-screen view of one stored file: a preview where possible (images, text, PDF), its
 * details, and actions to share, open in another app, edit, favorite and delete.
 */
@Composable
fun FileDetailScreen(fileId: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: FilesViewModel = viewModel(factory = FilesViewModel.factory(appContext))
    val loaded by produceState<FileLoad?>(null, fileId) {
        viewModel.observeFile(fileId).collect { value = FileLoad(it) }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        val current = loaded
        val file = current?.file
        when {
            current == null -> BackHandler(onBack = onClose) // still loading
            file == null -> LaunchedEffect(fileId) { onClose() } // deleted or never existed
            else -> DetailContent(file = file, viewModel = viewModel, onClose = onClose)
        }
    }
}

@Composable
private fun DetailContent(file: FileEntity, viewModel: FilesViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val preview by rememberPreviewState(file, viewModel::resolveBlob)
    var showEdit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pageWidthPx = context.resources.displayMetrics.widthPixels
    val isMissing = preview is PreviewState.Missing

    fun showMessage(text: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    fun external(action: ExternalAction) {
        scope.launch { launchExternal(context, viewModel, file, action) { showMessage(it) } }
    }

    BackHandler(onBack = onClose)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarButton(TazIcons.ChevronLeft, "Back", onClose)
                Spacer(Modifier.weight(1f))
                BarButton(
                    icon = if (file.isFavorite) TazIcons.StarFilled else TazIcons.Star,
                    description = if (file.isFavorite) "Remove from favorites" else "Add to favorites",
                    onClick = { viewModel.toggleFavorite(file) }
                )
                TextButton(onClick = { external(ExternalAction.SHARE) }, enabled = !isMissing) { Text("Share") }
                TextButton(onClick = { external(ExternalAction.OPEN) }, enabled = !isMissing) { Text("Open") }
                BarButton(TazIcons.Trash, "Delete", onClick = { confirmDelete = true })
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item(key = "header") {
                    Column(modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)) {
                        Text(
                            text = file.displayName,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${extensionOf(file.displayName).uppercase().ifEmpty { "FILE" }}  \u00B7  ${formatFileSize(file.sizeBytes)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                previewItems(preview, file.displayName, pageWidthPx)
                item(key = "details") {
                    DetailsSection(file = file, onEdit = { showEdit = true })
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
        )
    }

    if (showEdit) {
        EditFileDetailsDialog(
            file = file,
            onSave = { name, keywords, description ->
                viewModel.updateDetails(file, name, keywords, description)
                showEdit = false
            },
            onDismiss = { showEdit = false }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete file?") },
            text = {
                Text(
                    "\"${file.displayName}\" will be removed from TAZ, including its stored copy. " +
                        "The original file you imported is not affected. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteFile(file)
                    onClose()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DetailsSection(file: FileEntity, onEdit: () -> Unit) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Details",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onEdit) { Text("Edit") }
        }
        DetailRow("Original name", file.originalName)
        DetailRow("Type", "${file.mimeType} (${fileCategory(file.mimeType, file.displayName).label})")
        DetailRow("Size", formatFileSize(file.sizeBytes))
        DetailRow("Added", formatTimestamp(file.createdAt))
        if (file.updatedAt != file.createdAt) DetailRow("Details edited", formatTimestamp(file.updatedAt))
        DetailRow("Keywords", keywordList(file.keywords).joinToString(", ").ifEmpty { "None" })
        DetailRow("Description", file.description.ifBlank { "None" })
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatTimestamp(millis: Long): String =
    timestampFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
