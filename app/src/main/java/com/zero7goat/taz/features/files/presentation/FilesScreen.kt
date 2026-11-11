package com.zero7goat.taz.features.files.presentation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.database.FolderEntity
import com.zero7goat.taz.core.storage.extensionOf
import com.zero7goat.taz.core.ui.BulkTrashDialog
import com.zero7goat.taz.core.ui.CollectionHeader
import com.zero7goat.taz.core.ui.CollectionToolbar
import com.zero7goat.taz.core.ui.DeleteFolderDialog
import com.zero7goat.taz.core.ui.EmptyState
import com.zero7goat.taz.core.ui.FolderListRow
import com.zero7goat.taz.core.ui.FolderNameDialog
import com.zero7goat.taz.core.ui.FolderPathRow
import com.zero7goat.taz.core.ui.FolderPickerSheet
import com.zero7goat.taz.core.ui.ListSectionLabel
import com.zero7goat.taz.core.ui.RowStatusIcon
import com.zero7goat.taz.core.ui.SearchField
import com.zero7goat.taz.core.ui.SelectableRow
import com.zero7goat.taz.core.ui.SelectionAction
import com.zero7goat.taz.core.ui.TazFab
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.tags.BulkTagSheet
import com.zero7goat.taz.core.tags.TagTarget
import com.zero7goat.taz.core.ui.TagChipLine
import com.zero7goat.taz.core.tags.Tag
import com.zero7goat.taz.core.ui.folderPathLabel
import com.zero7goat.taz.core.ui.keepVisible
import com.zero7goat.taz.core.ui.rememberSelection
import com.zero7goat.taz.core.ui.replaceAllText
import com.zero7goat.taz.features.files.data.FileEntity
import com.zero7goat.taz.features.files.domain.formatFileSize
import kotlinx.coroutines.launch

/**
 * The files list with folders. Files are imported through the system file picker and copied
 * into TAZ's private storage. Tap a file to open its details and preview; long-press to select
 * several (then move, favourite or delete them together).
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

    var selection by rememberSelection()
    var moving by remember { mutableStateOf<Set<String>?>(null) }
    var trashing by remember { mutableStateOf<Set<String>?>(null) }
    var tagging by remember { mutableStateOf<Set<String>?>(null) }
    var fileToEdit by remember { mutableStateOf<FileEntity?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }
    var showSortFilter by remember { mutableStateOf(false) }

    val visibleIds = remember(state.files) { state.files.map { it.id } }
    LaunchedEffect(visibleIds) { selection = selection.keepVisible(visibleIds) }
    val selecting = selection.isNotEmpty()
    val selectedFiles = remember(state.files, selection) { state.files.filter { it.id in selection } }

    BackHandler(enabled = selecting) { selection = emptySet() }
    BackHandler(enabled = !selecting && state.isBrowsing && state.currentFolder != null) {
        viewModel.openFolder(state.path.getOrNull(state.path.size - 2)?.id)
    }

    // Opening a folder also clears any search or filter, so the folder's contents are shown.
    val openFolder: (String?) -> Unit = { folderId ->
        searchState.replaceAllText("")
        viewModel.setFavoritesOnly(false)
        viewModel.resetFilters()
        viewModel.openFolder(folderId)
    }
    val toggle: (String) -> Unit = { id -> selection = if (id in selection) selection - id else selection + id }

    // Actions that only make sense for exactly one file appear only then.
    val single = selectedFiles.singleOrNull()
    val moreActions = buildList {
        add(
            if (selectedFiles.all { it.isFavorite }) {
                SelectionAction("Remove from favorites") { viewModel.setFavorite(selectedFiles, false) }
            } else {
                SelectionAction("Add to favorites") { viewModel.setFavorite(selectedFiles, true) }
            }
        )
        add(SelectionAction("Add tags") { tagging = selection })
        if (single != null) {
            add(SelectionAction("Edit details") { fileToEdit = single })
            add(
                SelectionAction("Share") {
                    scope.launch {
                        launchExternal(context, viewModel, single, ExternalAction.SHARE) { latestOnMessage(it) }
                    }
                }
            )
        }
    }

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            CollectionHeader(
                title = "Files",
                selectedCount = selection.size,
                allSelected = selection.size == visibleIds.size,
                onClearSelection = { selection = emptySet() },
                onToggleSelectAll = {
                    selection = if (selection.size == visibleIds.size) emptySet() else visibleIds.toSet()
                },
                onMoveSelected = { moving = selection },
                onDeleteSelected = { trashing = selection },
                moreSelectionActions = moreActions
            )
            Spacer(Modifier.height(12.dp))
            SearchField(state = searchState, hint = "Search name, keywords or folder")
            Spacer(Modifier.height(8.dp))
            CollectionToolbar(
                favoritesOnly = state.query.favoritesOnly,
                onFavoritesOnlyChange = viewModel::setFavoritesOnly,
                canCreateFolder = state.isBrowsing && !selecting,
                onNewFolder = { creatingFolder = true },
                extra = {
                    FilterChip(
                        selected = state.query.hasActiveFilters,
                        onClick = { showSortFilter = true },
                        label = { Text("Sort & filter") },
                        leadingIcon = {
                            Icon(TazIcons.Sliders, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            )
            if (state.isBrowsing && state.currentFolder != null) {
                FolderPathRow(rootLabel = "Files", path = state.path, onNavigate = openFolder)
            }
            AnimatedVisibility(
                visible = importProgress != null,
                enter = expandVertically(tween(150)) + fadeIn(tween(150)),
                exit = shrinkVertically(tween(150)) + fadeOut(tween(150))
            ) {
                val progress = importProgress
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (progress != null) "Importing ${progress.current} of ${progress.total}" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            AnimatedContent(
                targetState = Triple(state.isLoading, state.currentFolder?.id, state.isBrowsing),
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) },
                label = "files-content"
            ) { (loading, _, _) ->
                val isEmpty = state.files.isEmpty() && state.folders.isEmpty()
                when {
                    loading -> Box(Modifier.fillMaxSize())
                    isEmpty -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            !state.isBrowsing -> EmptyState(
                                icon = TazIcons.Search,
                                title = "No matching files",
                                message = "Search looks at names, keywords, descriptions and folder names. Try other words or clear the filters."
                            )
                            state.currentFolder != null -> EmptyState(
                                icon = TazIcons.Folder,
                                title = "This folder is empty",
                                message = "Import files here, or move files in: long-press a file and choose Move."
                            )
                            else -> EmptyState(
                                icon = TazIcons.File,
                                title = "No files yet",
                                message = "Import documents, photos and more. TAZ keeps its own private copy on this device, and your original file is never changed or deleted."
                            )
                        }
                    }
                    else -> FilesList(
                        state = state,
                        selection = selection,
                        onOpenFile = onOpenFile,
                        onToggle = toggle,
                        onOpenFolder = { openFolder(it.id) },
                        onRenameFolder = { folderToRename = it },
                        onDeleteFolder = { folderToDelete = it }
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = !selecting,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            enter = scaleIn(tween(150)) + fadeIn(tween(150)),
            exit = scaleOut(tween(100)) + fadeOut(tween(100))
        ) {
            TazFab(label = "Import files", onClick = { importLauncher.launch(arrayOf("*/*")) })
        }
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
            location = folderPathLabel(state.allFolders, state.currentFolder?.id, "Files"),
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
        DeleteFolderDialog(
            folderName = folder.name,
            itemsNoun = "files",
            destination = folderPathLabel(state.allFolders, folder.parentId, "Files"),
            onConfirm = {
                viewModel.deleteFolder(folder)
                folderToDelete = null
            },
            onDismiss = { folderToDelete = null }
        )
    }

    moving?.let { ids ->
        val files = state.files.filter { it.id in ids }
        FolderPickerSheet(
            rootLabel = "Files",
            itemNoun = "file",
            itemCount = ids.size,
            folders = state.allFolders,
            currentFolderIds = files.map { it.folderId }.toSet(),
            onMove = { folderId ->
                viewModel.moveFiles(files, folderId)
                moving = null
                selection = emptySet()
            },
            onCreateFolder = viewModel::createFolderNow,
            onDismiss = { moving = null }
        )
    }

    fileToEdit?.let { file ->
        EditFileDetailsDialog(
            file = file,
            onSave = { name, keywords, description ->
                viewModel.updateDetails(file, name, keywords, description)
                fileToEdit = null
                selection = emptySet()
            },
            onDismiss = { fileToEdit = null }
        )
    }

    tagging?.let { ids ->
        BulkTagSheet(TagTarget.FILE, ids) { added ->
            tagging = null
            if (added) selection = emptySet()
        }
    }

    trashing?.let { ids ->
        BulkTrashDialog(
            count = ids.size,
            noun = "file",
            onConfirm = {
                viewModel.deleteFiles(state.files.filter { it.id in ids })
                trashing = null
                selection = emptySet()
            },
            onDismiss = { trashing = null }
        )
    }
}

@Composable
private fun FilesList(
    state: FilesUiState,
    selection: Set<String>,
    onOpenFile: (String) -> Unit,
    onToggle: (String) -> Unit,
    onOpenFolder: (FolderEntity) -> Unit,
    onRenameFolder: (FolderEntity) -> Unit,
    onDeleteFolder: (FolderEntity) -> Unit
) {
    val selecting = selection.isNotEmpty()
    val showLocation = !state.isBrowsing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        if (state.folders.isNotEmpty()) {
            item(key = "label-folders") { ListSectionLabel("Folders") }
            items(state.folders, key = { "folder-${it.id}" }) { folder ->
                FolderListRow(
                    folder = folder,
                    subtitle = null,
                    enabled = !selecting,
                    onOpen = { onOpenFolder(folder) },
                    onRename = { onRenameFolder(folder) },
                    onDelete = { onDeleteFolder(folder) },
                    modifier = Modifier.animateItem()
                )
            }
        }
        if (state.files.isNotEmpty()) {
            if (state.folders.isNotEmpty()) {
                item(key = "label-files") { ListSectionLabel("Files") }
            }
            items(state.files, key = { it.id }) { file ->
                FileRow(
                    file = file,
                    location = if (showLocation) folderPathLabel(state.allFolders, file.folderId, "Files") else null,
                    tags = state.tags[file.id].orEmpty(),
                    selected = file.id in selection,
                    selecting = selecting,
                    onOpen = { onOpenFile(file.id) },
                    onToggle = { onToggle(file.id) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: FileEntity,
    location: String?,
    tags: List<Tag>,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val typeLabel = extensionOf(file.displayName).uppercase().ifEmpty { "FILE" }
    SelectableRow(
        selected = selected,
        selectionMode = selecting,
        onOpen = onOpen,
        onToggleSelected = onToggle,
        modifier = modifier,
        leading = {
            // The same round badge as login rows, with an icon for the kind of file.
            Box(
                modifier = Modifier
                    .padding(end = 14.dp)
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = fileIconFor(file.mimeType, file.displayName),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.displayName,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = listOfNotNull("$typeLabel  ·  ${formatFileSize(file.sizeBytes)}", location)
                    .joinToString("  ·  "),
                modifier = Modifier.padding(top = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            TagChipLine(tags, modifier = Modifier.padding(top = 6.dp))
        }
        if (file.isFavorite) RowStatusIcon(TazIcons.StarFilled, "Favorite")
    }
}
