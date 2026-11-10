@file:OptIn(ExperimentalFoundationApi::class)

package com.zero7goat.taz.features.notes.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
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
import com.zero7goat.taz.core.ui.EmptyState
import com.zero7goat.taz.core.ui.ScreenHeader
import com.zero7goat.taz.core.ui.SearchField
import com.zero7goat.taz.core.ui.TazFab
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.core.ui.replaceAllText
import com.zero7goat.taz.features.notes.data.NoteEntity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.zero7goat.taz.features.notes.domain.NoteSearch
import java.util.UUID

/**
 * The notes list with folders. [onOpenNote] opens the editor; a brand-new note gets a fresh
 * id and is only stored once it has content.
 */
@Composable
fun NotesScreen(onOpenNote: (String) -> Unit, modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: NotesViewModel = viewModel(factory = NotesViewModel.factory(appContext))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val searchState = rememberTextFieldState()
    LaunchedEffect(searchState) {
        snapshotFlow { searchState.text.toString() }.collect { viewModel.onQueryChange(it) }
    }

    var noteToDelete by remember { mutableStateOf<NoteEntity?>(null) }
    var noteToMove by remember { mutableStateOf<NoteEntity?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }

    // Back inside a folder goes up one level instead of leaving the app.
    BackHandler(enabled = state.isBrowsing && state.currentFolder != null) {
        viewModel.openFolder(state.path.getOrNull(state.path.size - 2)?.id)
    }

    // Opening a folder also clears any search or filter, so the folder's contents are shown.
    val openFolder: (String?) -> Unit = { folderId ->
        searchState.replaceAllText("")
        viewModel.setFavoritesOnly(false)
        viewModel.openFolder(folderId)
    }

    val actions = remember(viewModel, onOpenNote) {
        ListActions(
            onOpenNote = onOpenNote,
            onOpenFolder = { openFolder(it.id) },
            onTogglePinned = viewModel::togglePinned,
            onToggleFavorite = viewModel::toggleFavorite,
            onMoveNote = { noteToMove = it },
            onDeleteNote = { noteToDelete = it },
            onRenameFolder = { folderToRename = it },
            onDeleteFolder = { folderToDelete = it }
        )
    }

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader("Notes")
            Spacer(Modifier.height(16.dp))
            SearchField(state = searchState, hint = "Search notes")
            Spacer(Modifier.height(12.dp))
            FilterRow(
                favoritesOnly = state.favoritesOnly,
                canCreateFolder = state.isBrowsing,
                onFavoritesOnlyChange = viewModel::setFavoritesOnly,
                onNewFolder = { creatingFolder = true }
            )
            if (state.isBrowsing && state.currentFolder != null) {
                PathRow(path = state.path, onNavigate = openFolder)
            } else {
                Spacer(Modifier.height(4.dp))
            }

            val isEmpty = state.notes.isEmpty() && state.folders.isEmpty()
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
                            title = "No matching notes",
                            message = "Try a different search or filter."
                        )
                        state.currentFolder != null -> EmptyState(
                            icon = TazIcons.Folder,
                            title = "This folder is empty",
                            message = "Add a note here, or create a folder inside it."
                        )
                        else -> EmptyState(
                            icon = TazIcons.Notes,
                            title = "No notes yet",
                            message = "Write down ideas, lists and drafts. Everything stays on this device."
                        )
                    }
                }
                else -> NotesList(
                    folders = state.folders,
                    notes = state.notes,
                    query = state.query,
                    actions = actions,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        TazFab(
            label = "New note",
            onClick = { onOpenNote(UUID.randomUUID().toString()) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
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
                        "Notes inside are kept and moved to the top level."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder)
                    folderToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) { Text("Cancel") }
            }
        )
    }

    noteToMove?.let { note ->
        MoveToFolderDialog(
            folders = state.allFolders,
            currentFolderId = note.folderId,
            onSelect = { folderId ->
                viewModel.moveNote(note, folderId)
                noteToMove = null
            },
            onDismiss = { noteToMove = null }
        )
    }

    noteToDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("Move note to trash?") },
            text = { Text("It will be moved to the trash. You can restore it for 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteNote(note)
                    noteToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

/** Everything a list row can ask the screen to do, bundled to keep signatures short. */
private class ListActions(
    val onOpenNote: (String) -> Unit,
    val onOpenFolder: (FolderEntity) -> Unit,
    val onTogglePinned: (NoteEntity) -> Unit,
    val onToggleFavorite: (NoteEntity) -> Unit,
    val onMoveNote: (NoteEntity) -> Unit,
    val onDeleteNote: (NoteEntity) -> Unit,
    val onRenameFolder: (FolderEntity) -> Unit,
    val onDeleteFolder: (FolderEntity) -> Unit
)

@Composable
private fun FilterRow(
    favoritesOnly: Boolean,
    canCreateFolder: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onNewFolder: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        Spacer(Modifier.weight(1f))
        if (canCreateFolder) {
            TextButton(onClick = onNewFolder) { Text("New folder") }
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
        PathSegment(text = "Notes", isCurrent = false) { onNavigate(null) }
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
        color = if (isCurrent) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
        )
    )
}

@Composable
private fun NotesList(
    folders: List<FolderEntity>,
    notes: List<NoteEntity>,
    query: String,
    actions: ListActions,
    modifier: Modifier = Modifier
) {
    val pinned = notes.filter { it.isPinned }
    val others = notes.filterNot { it.isPinned }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        if (folders.isNotEmpty()) {
            item(key = "label-folders") { ListLabel("Folders") }
            items(folders, key = { "folder-${it.id}" }) { folder ->
                FolderRow(folder, actions, Modifier.animateItem())
            }
        }
        if (pinned.isNotEmpty()) {
            item(key = "label-pinned") { ListLabel("Pinned") }
            items(pinned, key = { it.id }) { note -> NoteRow(note, query, actions, Modifier.animateItem()) }
        }
        if (others.isNotEmpty()) {
            if (pinned.isNotEmpty() || folders.isNotEmpty()) {
                item(key = "label-others") { ListLabel("Notes") }
            }
            items(others, key = { it.id }) { note -> NoteRow(note, query, actions, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun ListLabel(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    )
}

@Composable
private fun FolderRow(folder: FolderEntity, actions: ListActions, modifier: Modifier = Modifier) {
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
private fun NoteRow(note: NoteEntity, query: String, actions: ListActions, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val color = noteColor(note.colorIndex)

    Box(modifier = modifier) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { actions.onOpenNote(note.id) },
                        onLongClick = { menuOpen = true }
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (color != null) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp, end = 12.dp)
                            .size(10.dp)
                            .background(color, CircleShape)
                    )
                }
                // The list shows the title; while searching it also shows where the text matched.
                val snippet = remember(note.description, note.body, query) {
                    NoteSearch.snippet(note.description, note.body, query)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = note.title.ifBlank { "Untitled" },
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (snippet != null) {
                        Text(
                            text = highlighted(snippet, query),
                            modifier = Modifier.padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (note.isPinned) {
                    Icon(
                        imageVector = TazIcons.PinFilled,
                        contentDescription = "Pinned",
                        modifier = Modifier
                            .padding(start = 12.dp, top = 2.dp)
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (note.isFavorite) {
                    Icon(
                        imageVector = TazIcons.StarFilled,
                        contentDescription = "Favorite",
                        modifier = Modifier
                            .padding(start = 8.dp, top = 2.dp)
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
                text = { Text(if (note.isPinned) "Unpin" else "Pin") },
                onClick = {
                    menuOpen = false
                    actions.onTogglePinned(note)
                }
            )
            DropdownMenuItem(
                text = { Text(if (note.isFavorite) "Remove from favorites" else "Add to favorites") },
                onClick = {
                    menuOpen = false
                    actions.onToggleFavorite(note)
                }
            )
            DropdownMenuItem(
                text = { Text("Move to folder") },
                onClick = {
                    menuOpen = false
                    actions.onMoveNote(note)
                }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    actions.onDeleteNote(note)
                }
            )
        }
    }
}

/** [text] with the words of [query] in bold. */
private fun highlighted(text: String, query: String): AnnotatedString =
    buildAnnotatedString {
        append(text)
        NoteSearch.highlightRanges(text, query).forEach { range ->
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
        }
    }
