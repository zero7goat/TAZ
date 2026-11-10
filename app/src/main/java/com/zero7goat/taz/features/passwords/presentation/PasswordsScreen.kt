package com.zero7goat.taz.features.passwords.presentation

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.zero7goat.taz.core.ui.folderPathLabel
import com.zero7goat.taz.core.ui.keepVisible
import com.zero7goat.taz.core.ui.rememberSelection
import com.zero7goat.taz.core.ui.replaceAllText
import com.zero7goat.taz.features.passwords.domain.CredentialListItem
import java.util.UUID

/**
 * The saved logins.
 *
 * Layout, from most to least used: search, then the list itself (tap to open, long-press to select
 * several). The Favorites filter and New folder sit in one quiet row under the search field. The
 * password generator has its own key button in the header, and is also inside every login form
 * next to the password field. Password health is the first row of the list, where it is visible
 * without competing with the logins. Rows show only the service name and username.
 */
@Composable
fun PasswordsScreen(
    onOpenCredential: (String) -> Unit,
    onOpenHealth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: PasswordsViewModel = viewModel(factory = PasswordsViewModel.factory(appContext))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val searchState = rememberTextFieldState()
    LaunchedEffect(searchState) {
        snapshotFlow { searchState.text.toString() }.collect { viewModel.onQueryChange(it) }
    }

    var selection by rememberSelection()
    var moving by remember { mutableStateOf<Set<String>?>(null) }
    var trashing by remember { mutableStateOf<Set<String>?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }
    var showGenerator by remember { mutableStateOf(false) }

    val visibleIds = remember(state.items) { state.items.map { it.id } }
    LaunchedEffect(visibleIds) { selection = selection.keepVisible(visibleIds) }
    val selecting = selection.isNotEmpty()
    val selectedItems = remember(state.items, selection) { state.items.filter { it.id in selection } }

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
                title = "Passwords",
                selectedCount = selection.size,
                allSelected = selection.size == visibleIds.size,
                onClearSelection = { selection = emptySet() },
                onToggleSelectAll = {
                    selection = if (selection.size == visibleIds.size) emptySet() else visibleIds.toSet()
                },
                onMoveSelected = { moving = selection },
                onDeleteSelected = { trashing = selection },
                moreSelectionActions = listOf(
                    if (selectedItems.all { it.isFavorite }) {
                        SelectionAction("Remove from favorites") { viewModel.setFavorite(selection, false) }
                    } else {
                        SelectionAction("Add to favorites") { viewModel.setFavorite(selection, true) }
                    }
                ),
                actions = {
                    IconButton(onClick = { showGenerator = true }) {
                        Icon(TazIcons.Key, contentDescription = "Password generator")
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
            SearchField(state = searchState, hint = "Search by website")
            Spacer(Modifier.height(8.dp))
            CollectionToolbar(
                favoritesOnly = state.favoritesOnly,
                onFavoritesOnlyChange = viewModel::setFavoritesOnly,
                canCreateFolder = state.isBrowsing && !selecting,
                onNewFolder = { creatingFolder = true }
            )
            if (state.isBrowsing && state.currentFolder != null) {
                FolderPathRow(rootLabel = "Passwords", path = state.path, onNavigate = openFolder)
            }

            AnimatedContent(
                targetState = Triple(state.isLoading, state.currentFolder?.id, state.isBrowsing),
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) },
                label = "passwords-content"
            ) { (loading, _, _) ->
                val isEmpty = state.items.isEmpty() && state.folders.isEmpty()
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
                                title = "No matching logins",
                                message = "Search looks at website addresses. Try another address, or turn off the Favorites filter."
                            )
                            state.currentFolder != null -> EmptyState(
                                icon = TazIcons.Folder,
                                title = "This folder is empty",
                                message = "Add a login here, or move logins in: long-press a login and choose Move."
                            )
                            else -> EmptyState(
                                icon = TazIcons.Lock,
                                title = "No passwords yet",
                                message = "Keep your logins in one private place. They are encrypted and never leave this device."
                            )
                        }
                    }
                    else -> PasswordsList(
                        state = state,
                        selection = selection,
                        showHealthEntry = state.isBrowsing && state.currentFolder == null && !selecting,
                        onOpenHealth = onOpenHealth,
                        onOpenItem = onOpenCredential,
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
            TazFab(label = "Add password", onClick = { onOpenCredential(UUID.randomUUID().toString()) })
        }
    }

    if (showGenerator) {
        PasswordGeneratorDialog(onDismiss = { showGenerator = false })
    }

    if (creatingFolder) {
        FolderNameDialog(
            title = "New folder",
            initialName = "",
            confirmLabel = "Create",
            location = folderPathLabel(state.allFolders, state.currentFolder?.id, "Passwords"),
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
            itemsNoun = "logins",
            destination = folderPathLabel(state.allFolders, folder.parentId, "Passwords"),
            onConfirm = {
                viewModel.deleteFolder(folder)
                folderToDelete = null
            },
            onDismiss = { folderToDelete = null }
        )
    }

    moving?.let { ids ->
        FolderPickerSheet(
            rootLabel = "Passwords",
            itemNoun = "login",
            itemCount = ids.size,
            folders = state.allFolders,
            currentFolderIds = state.items.filter { it.id in ids }.map { it.folderId }.toSet(),
            onMove = { folderId ->
                viewModel.moveCredentials(ids, folderId)
                moving = null
                selection = emptySet()
            },
            onCreateFolder = viewModel::createFolderNow,
            onDismiss = { moving = null }
        )
    }

    trashing?.let { ids ->
        BulkTrashDialog(
            count = ids.size,
            noun = "login",
            onConfirm = {
                viewModel.deleteCredentials(ids)
                trashing = null
                selection = emptySet()
            },
            onDismiss = { trashing = null }
        )
    }
}

@Composable
private fun PasswordsList(
    state: PasswordsUiState,
    selection: Set<String>,
    showHealthEntry: Boolean,
    onOpenHealth: () -> Unit,
    onOpenItem: (String) -> Unit,
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
        if (showHealthEntry) {
            item(key = "health") { HealthEntryRow(onOpenHealth, Modifier.animateItem()) }
        }
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
        if (state.items.isNotEmpty()) {
            if (state.folders.isNotEmpty() || showHealthEntry) {
                item(key = "label-logins") { ListSectionLabel("Logins") }
            }
            items(state.items, key = { it.id }) { item ->
                CredentialRow(
                    item = item,
                    location = if (showLocation) folderPathLabel(state.allFolders, item.folderId, "Passwords") else null,
                    selected = item.id in selection,
                    selecting = selecting,
                    onOpen = { onOpenItem(item.id) },
                    onToggle = { onToggle(item.id) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

/** The way into Password health: a single quiet row at the top of the list, not a banner. */
@Composable
private fun HealthEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(TazIcons.Shield, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Password health",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Find weak, reused and old passwords",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Icon(
                imageVector = TazIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CredentialRow(
    item: CredentialListItem,
    location: String?,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    SelectableRow(
        selected = selected,
        selectionMode = selecting,
        onOpen = onOpen,
        onToggleSelected = onToggle,
        modifier = modifier,
        leading = { Monogram(item.serviceName) }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.serviceName,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            val secondary = listOfNotNull(item.username.ifEmpty { null }, location).joinToString("  ·  ")
            if (secondary.isNotEmpty()) {
                Text(
                    text = secondary,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (item.isFavorite) RowStatusIcon(TazIcons.StarFilled, "Favorite")
    }
}

/** First letter of the service in a neutral circle, so logins are easy to scan. */
@Composable
private fun Monogram(name: String) {
    Box(
        modifier = Modifier
            .padding(end = 14.dp)
            .size(36.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium
        )
    }
}
