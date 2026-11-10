@file:OptIn(ExperimentalFoundationApi::class)

package com.zero7goat.taz.features.files.presentation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.database.FolderEntity
import com.zero7goat.taz.core.storage.extensionOf
import com.zero7goat.taz.core.ui.EmptyState
import com.zero7goat.taz.core.ui.ScreenHeader
import com.zero7goat.taz.core.ui.SearchField
import com.zero7goat.taz.core.ui.TazFab
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.core.ui.replaceAllText
import com.zero7goat.taz.features.files.data.FileEntity
import com.zero7goat.taz.features.files.domain.formatFileSize
import com.zero7goat.taz.features.notes.presentation.FolderNameDialog
import com.zero7goat.taz.features.notes.presentation.MoveToFolderDialog
import kotlinx.coroutines.launch

/**
 * The files list with folders. Files are imported through the system file picker and copied
 * into TAZ's private storage. [onOpenFile] opens the detail and preview screen.
 */
@Composable
fun FilesScreen(
    onOpenFile: (String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: FilesViewModel = viewModel(factory = FilesViewModel.factory(context.applicationContext))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val latestOnMessage by rememberUpdatedState(onMessage)

    val searchState = rememberTextFieldState()
    LaunchedEffect(searchState) {
        snapshotFlow { searchState.text.toString() }.collect { viewModel.onQueryChange(it) }
    }
    LaunchedEffect(viewModel) { viewModel.messages.collect { latestOnMessage(it) } }

    // The system picker: any type of file, several at once. The app only receives temporary read access.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importFiles(uris) }

    var fileToDelete by remember { mutableStateOf<FileEntity?>(null) }
    var fileToMove by remember { mutableStateOf<FileEntity?>(null) }
    var fileToEdit by remember { mutableStateOf<FileEntity?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }
    var showSortFilter by remember { mutableStateOf(false) }

    // Back inside a folder goes up one level instead of leaving the app.
    BackHandler(enabled = state.isBrowsing && state.currentFolder != null) {
        viewModel.openFolder(state.path.getOrNull(state.path.size - 2)?.id)
    }

    // Opening a folder also clears any search or filter, so the folder's contents are shown.
    val openFolder: (String?) -> Unit = { folderId ->
        searchState.replaceAllText("")
        viewModel.setFavoritesOnly(false)
        viewModel.resetFilters()
        viewModel.openFolder(folderId)
    }

    val actions = remember(viewModel, onOpenFile) {
        FileListActions(
            onOpenFile = onOpenFile,
            onOpenFolder = { openFolder(it.id) },
            onToggleFavorite = viewModel::toggleFavorite,
            onEdit = { fileToEdit = it },
            onMove = { fileToMove = it },
            onShare = { file ->
                scope.launch {
                    launchExternal(context, viewModel, file, ExternalAction.SHARE) { latestOnMessage(it) }
                }
            },
            onDelete = { fileToDelete = it },
            onRenameFolder = { folderToRename = it },
            onDeleteFolder = { folderToDelete = it }
        )
    }

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader("Files")
            Spacer(Modifier.height(16.dp))
            SearchField(state = searchState, hint = "Search name, keywords or folder")
            Spacer(Modifier.height(12.dp))
            ChipRow(
                favoritesOnly = state.query.favoritesOnly,
                hasActiveFilters = state.query.hasActiveFilters,
                canCreateFolder = state.isBrowsing,
                onFavoritesOnlyChange = viewModel::setFavoritesOnly,
                onSortFilter = { showSortFilter = true },
                onNewFolder = { creatingFolder = true }
            )
            if (state.isBrowsing && state.currentFolder != null) {
                PathRow(path = state.path, onNavigate = openFolder)
            } else {
                Spacer(Modifier.height(4.dp))
            }
            importProgress?.let { progress ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Importing ${progress.current} of ${progress.total}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            val isEmpty = state.files.isEmpty() && state.folders.isEmpty()
            when {
                state.isLoading -> Box(Modifier.weight(1f))
                isEmpty -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        !state.isBrowsing -> EmptyState(
                            icon = TazIcons.Search,
                            title = "No matching files",
                            message = "Search looks at names, keywords, descriptions and folder names. Try a different search or filter."
                        )
                        state.currentFolder != null -> EmptyState(
                            icon = TazIcons.Folder,
                            title = "This folder is empty",
                            message = "Import files here, or create a folder inside it."
                        )
                        else -> EmptyState(
                            icon = TazIcons.File,
                            title = "No files yet",
                            message = "Import documents, photos and more. TAZ keeps its own private copy on this device, and your original file is never changed or deleted."
                        )
                    }
                }
                else -> FilesList(
                    folders = state.folders,
                    files = state.files,
                    actions = actions,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        TazFab(
            label = "Import files",
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }

    if (showSortFilter) {
        SortFilterDialog(
            query = state.query,
            onSort = viewModel::setSort,
            onType = viewModel::setTypeFilter,
            onSize = viewModel::setSizeFilter,
            onDate = viewModel::setDateFilter,
            onReset = viewModel::resetFilters,
            onDismiss = { showSortFilter = false }
        )
    }

    if (creatingFolder) {
        FolderNameDialog(
            title = "New folder",
            initialName = "",
            confirmLabel = "Create",
            onConfirm = {
                viewModel.createFolder(it)
                creatingFolder = false
            },
            onDismiss = { creatingFolder = false }
        )
    }

    folderToRename?.let { folder ->
        FolderNameDialog(
            title = "Rename folder",
            initialName = folder.name,
            confirmLabel = "Rename",
            onConfirm = {
                viewModel.renameFolder(folder, it)
                folderToRename = null
            },
            onDismiss = { folderToRename = null }
        )
    }

    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("Delete folder?") },
            text = {
                Text(
                    "\"${folder.name}\" and its subfolders will be deleted. " +
                        "Files inside are kept and moved to the top level."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder)
                    folderToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text("Cancel") } }
        )
    }

    fileToMove?.let { file ->
        MoveToFolderDialog(
            folders = state.allFolders,
            currentFolderId = file.folderId,
            onSelect = { folderId ->
                viewModel.moveFile(file, folderId)
                fileToMove = null
            },
            onDismiss = { fileToMove = null }
        )
    }

    fileToEdit?.let { file ->
        EditFileDetailsDialog(
            file = file,
            onSave = { name, keywords, description ->
                viewModel.updateDetails(file, name, keywords, description)
                fileToEdit = null
            },
            onDismiss = { fileToEdit = null }
        )
    }

    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Move file to trash?") },
            text = {
                Text(
                    "\"${file.displayName}\" will be moved to the trash. You can restore it for 30 days. " +
                        "The original file you imported is not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFile(file)
                    fileToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { fileToDelete = null }) { Text("Cancel") } }
        )
    }
}

/** Everything a row can ask the screen to do, bundled to keep signatures short. */
private class FileListActions(
    val onOpenFile: (String) -> Unit,
    val onOpenFolder: (FolderEntity) -> Unit,
    val onToggleFavorite: (FileEntity) -> Unit,
    val onEdit: (FileEntity) -> Unit,
    val onMove: (FileEntity) -> Unit,
    val onShare: (FileEntity) -> Unit,
    val onDelete: (FileEntity) -> Unit,
    val onRenameFolder: (FolderEntity) -> Unit,
    val onDeleteFolder: (FolderEntity) -> Unit
)

@Composable
private fun ChipRow(
    favoritesOnly: Boolean,
    hasActiveFilters: Boolean,
    canCreateFolder: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onSortFilter: () -> Unit,
    onNewFolder: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = !favoritesOnly,
            onClick = { onFavoritesOnlyChange(false) },
            label = { Text("All") }
        )
        FilterChip(
            selected = favoritesOnly,
            onClick = { onFavoritesOnlyChange(true) },
            label = { Text("Favorites") }
        )
        FilterChip(
            selected = hasActiveFilters,
            onClick = onSortFilter,
            label = { Text("Sort & filter") }
        )
        if (canCreateFolder) {
            AssistChip(onClick = onNewFolder, label = { Text("New folder") })
        }
    }
}

@Composable
private fun PathRow(path: List<FolderEntity>, onNavigate: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PathSegment(text = "Files", isCurrent = false) { onNavigate(null) }
        path.forEachIndexed { index, folder ->
            Icon(
                imageVector = TazIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            PathSegment(text = folder.name, isCurrent = index == path.lastIndex) {
                onNavigate(folder.id)
            }
        }
    }
}

@Composable
private fun PathSegment(text: String, isCurrent: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !isCurrent, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
        )
    )
}

@Composable
private fun FilesList(
    folders: List<FolderEntity>,
    files: List<FileEntity>,
    actions: FileListActions,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        if (folders.isNotEmpty()) {
            item(key = "label-folders") { ListLabel("Folders") }
            items(folders, key = { "folder-${it.id}" }) { folder -> FolderRow(folder, actions, Modifier.animateItem()) }
        }
        if (files.isNotEmpty()) {
            if (folders.isNotEmpty()) {
                item(key = "label-files") { ListLabel("Files") }
            }
            items(files, key = { it.id }) { file -> FileRow(file, actions, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun ListLabel(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    )
}

@Composable
private fun FolderRow(folder: FolderEntity, actions: FileListActions, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { actions.onOpenFolder(folder) },
                        onLongClick = { menuOpen = true }
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = TazIcons.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = folder.name,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium
                )
                Icon(
                    imageVector = TazIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    menuOpen = false
                    actions.onRenameFolder(folder)
                }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    actions.onDeleteFolder(folder)
                }
            )
        }
    }
}

@Composable
private fun FileRow(file: FileEntity, actions: FileListActions, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val typeLabel = extensionOf(file.displayName).uppercase().ifEmpty { "FILE" }

    Box(modifier = modifier) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { actions.onOpenFile(file.id) },
                        onLongClick = { menuOpen = true }
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = TazIcons.File,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.displayName,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$typeLabel  \u00B7  ${formatFileSize(file.sizeBytes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (file.isFavorite) {
                    Icon(
                        imageVector = TazIcons.StarFilled,
                        contentDescription = "Favorite",
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (file.isFavorite) "Remove from favorites" else "Add to favorites") },
                onClick = {
                    menuOpen = false
                    actions.onToggleFavorite(file)
                }
            )
            DropdownMenuItem(
                text = { Text("Edit details") },
                onClick = {
                    menuOpen = false
                    actions.onEdit(file)
                }
            )
            DropdownMenuItem(
                text = { Text("Move to folder") },
                onClick = {
                    menuOpen = false
                    actions.onMove(file)
                }
            )
            DropdownMenuItem(
                text = { Text("Share") },
                onClick = {
                    menuOpen = false
                    actions.onShare(file)
                }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    actions.onDelete(file)
                }
            )
        }
    }
}
