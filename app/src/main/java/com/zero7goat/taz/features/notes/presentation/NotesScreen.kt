package com.zero7goat.taz.features.notes.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.database.FolderEntity
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
import com.zero7goat.taz.core.ui.folderPathLabel
import com.zero7goat.taz.core.ui.keepVisible
import com.zero7goat.taz.core.ui.rememberSelection
import com.zero7goat.taz.core.ui.replaceAllText
import com.zero7goat.taz.features.notes.data.NoteEntity
import com.zero7goat.taz.features.notes.domain.NoteSearch
import java.util.UUID

/**
 * The notes list with folders. Tap a note to open it, long-press to start selecting several
 * (then move, pin, favourite or delete them together). A brand-new note gets a fresh id and is
 * only stored once it has content.
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

    var selection by rememberSelection()
    var moving by remember { mutableStateOf<Set<String>?>(null) }
    var trashing by remember { mutableStateOf<Set<String>?>(null) }
    var tagging by remember { mutableStateOf<Set<String>?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }

    // A selection only ever covers notes that are on screen right now.
    val visibleIds = remember(state.notes) { state.notes.map { it.id } }
    LaunchedEffect(visibleIds) { selection = selection.keepVisible(visibleIds) }
    val selecting = selection.isNotEmpty()
    val selectedNotes = remember(state.notes, selection) { state.notes.filter { it.id in selection } }

    // Back first ends selection, then goes up one folder level, then leaves as usual.
    BackHandler(enabled = selecting) { selection = emptySet() }
    BackHandler(enabled = !selecting && state.isBrowsing && state.currentFolder != null) {
        viewModel.openFolder(state.path.getOrNull(state.path.size - 2)?.id)
    }

    // Opening a folder also clears any search or filter, so the folder's contents are shown.
    val openFolder: (String?) -> Unit = { folderId ->
        searchState.replaceAllText("")
        viewModel.setFavoritesOnly(false)
        viewModel.openFolder(folderId)
    }
    val toggle: (String) -> Unit = { id -> selection = if (id in selection) selection - id else selection + id }

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            CollectionHeader(
                title = "Notes",
                selectedCount = selection.size,
                allSelected = selection.size == visibleIds.size,
                onClearSelection = { selection = emptySet() },
                onToggleSelectAll = {
                    selection = if (selection.size == visibleIds.size) emptySet() else visibleIds.toSet()
                },
                onMoveSelected = { moving = selection },
                onDeleteSelected = { trashing = selection },
                moreSelectionActions = listOf(
                    if (selectedNotes.all { it.isPinned }) {
                        SelectionAction("Unpin") { viewModel.setPinned(selection, false) }
                    } else {
                        SelectionAction("Pin") { viewModel.setPinned(selection, true) }
                    },
                    if (selectedNotes.all { it.isFavorite }) {
                        SelectionAction("Remove from favorites") { viewModel.setFavorite(selection, false) }
                    } else {
                        SelectionAction("Add to favorites") { viewModel.setFavorite(selection, true) }
                    },
                    SelectionAction("Add tags") { tagging = selection }
                )
            )
            Spacer(Modifier.height(12.dp))
            SearchField(state = searchState, hint = "Search notes")
            Spacer(Modifier.height(8.dp))
            CollectionToolbar(
                favoritesOnly = state.favoritesOnly,
                onFavoritesOnlyChange = viewModel::setFavoritesOnly,
                canCreateFolder = state.isBrowsing && !selecting,
                onNewFolder = { creatingFolder = true }
            )
            if (state.isBrowsing && state.currentFolder != null) {
                FolderPathRow(rootLabel = "Notes", path = state.path, onNavigate = openFolder)
            }

            // Content cross-fades when another folder opens or a search starts, instead of jumping.
            AnimatedContent(
                targetState = ContentKey(state.isLoading, state.currentFolder?.id, state.isBrowsing),
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) },
                contentKey = { it },
                label = "notes-content"
            ) { key ->
                val isEmpty = state.notes.isEmpty() && state.folders.isEmpty()
                when {
                    key.loading -> Box(Modifier.fillMaxSize())
                    isEmpty -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            !state.isBrowsing -> EmptyState(
                                icon = TazIcons.Search,
                                title = "No matching notes",
                                message = "Try other words, or turn off the Favorites filter."
                            )
                            state.currentFolder != null -> EmptyState(
                                icon = TazIcons.Folder,
                                title = "This folder is empty",
                                message = "Write a note here, or move notes in: long-press a note and choose Move."
                            )
                            else -> EmptyState(
                                icon = TazIcons.Notes,
                                title = "No notes yet",
                                message = "Write down ideas, lists and drafts. Everything stays on this device."
                            )
                        }
                    }
                    else -> NotesList(
                        state = state,
                        selection = selection,
                        onOpenNote = onOpenNote,
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
            TazFab(label = "New note", onClick = { onOpenNote(UUID.randomUUID().toString()) })
        }
    }

    if (creatingFolder) {
        FolderNameDialog(
            title = "New folder",
            initialName = "",
            confirmLabel = "Create",
            location = folderPathLabel(state.allFolders, state.currentFolder?.id, "Notes"),
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
            itemsNoun = "notes",
            destination = folderPathLabel(state.allFolders, folder.parentId, "Notes"),
            onConfirm = {
                viewModel.deleteFolder(folder)
                folderToDelete = null
            },
            onDismiss = { folderToDelete = null }
        )
    }

    moving?.let { ids ->
        FolderPickerSheet(
            rootLabel = "Notes",
            itemNoun = "note",
            itemCount = ids.size,
            folders = state.allFolders,
            currentFolderIds = state.notes.filter { it.id in ids }.map { it.folderId }.toSet(),
            onMove = { folderId ->
                viewModel.moveNotes(ids, folderId)
                moving = null
                selection = emptySet()
            },
            onCreateFolder = viewModel::createFolderNow,
            onDismiss = { moving = null }
        )
    }

    tagging?.let { ids ->
        BulkTagSheet(TagTarget.NOTE, ids) { added ->
            tagging = null
            if (added) selection = emptySet()
        }
    }

    trashing?.let { ids ->
        BulkTrashDialog(
            count = ids.size,
            noun = "note",
            onConfirm = {
                viewModel.deleteNotes(ids)
                trashing = null
                selection = emptySet()
            },
            onDismiss = { trashing = null }
        )
    }
}

/** What the content area is showing; a change of key cross-fades the list. */
private data class ContentKey(val loading: Boolean, val folderId: String?, val browsing: Boolean)

@Composable
private fun NotesList(
    state: NotesUiState,
    selection: Set<String>,
    onOpenNote: (String) -> Unit,
    onToggle: (String) -> Unit,
    onOpenFolder: (FolderEntity) -> Unit,
    onRenameFolder: (FolderEntity) -> Unit,
    onDeleteFolder: (FolderEntity) -> Unit
) {
    val pinned = state.notes.filter { it.isPinned }
    val others = state.notes.filterNot { it.isPinned }
    val selecting = selection.isNotEmpty()
    // Outside a folder (search, favorites) each note says which folder it lives in.
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
        if (pinned.isNotEmpty()) {
            item(key = "label-pinned") { ListSectionLabel("Pinned") }
            items(pinned, key = { it.id }) { note ->
                NoteRow(note, state, showLocation, note.id in selection, selecting, onOpenNote, onToggle, Modifier.animateItem())
            }
        }
        if (others.isNotEmpty()) {
            if (pinned.isNotEmpty() || state.folders.isNotEmpty()) {
                item(key = "label-others") { ListSectionLabel("Notes") }
            }
            items(others, key = { it.id }) { note ->
                NoteRow(note, state, showLocation, note.id in selection, selecting, onOpenNote, onToggle, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun NoteRow(
    note: NoteEntity,
    state: NotesUiState,
    showLocation: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onOpenNote: (String) -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = noteColor(note.colorIndex)
    val query = state.query
    // While searching, a snippet shows where the text matched.
    val snippet = remember(note.description, note.body, query) {
        NoteSearch.snippet(note.description, note.body, query)
    }
    SelectableRow(
        selected = selected,
        selectionMode = selecting,
        onOpen = { onOpenNote(note.id) },
        onToggleSelected = { onToggle(note.id) },
        modifier = modifier,
        tint = noteTint(note.colorIndex),
        leading = {
            if (accent != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 14.dp)
                        .size(10.dp)
                        .background(accent, CircleShape)
                )
            }
        }
    ) {
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
            if (showLocation) {
                Text(
                    text = folderPathLabel(state.allFolders, note.folderId, "Notes"),
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TagChipLine(state.tags[note.id].orEmpty(), modifier = Modifier.padding(top = 6.dp))
        }
        if (note.isPinned) RowStatusIcon(TazIcons.PinFilled, "Pinned")
        if (note.isFavorite) RowStatusIcon(TazIcons.StarFilled, "Favorite")
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
