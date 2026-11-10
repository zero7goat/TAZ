@file:OptIn(ExperimentalFoundationApi::class)

package com.zero7goat.taz.features.passwords.presentation

import androidx.activity.compose.BackHandler
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
import com.zero7goat.taz.core.ui.replaceAllText
import com.zero7goat.taz.core.ui.EmptyState
import com.zero7goat.taz.core.ui.ScreenHeader
import com.zero7goat.taz.core.ui.SearchField
import com.zero7goat.taz.core.ui.TazFab
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.notes.presentation.FolderNameDialog
import com.zero7goat.taz.features.notes.presentation.MoveToFolderDialog
import com.zero7goat.taz.features.passwords.domain.CredentialListItem
import java.util.UUID

/**
 * The saved logins list with folders. Rows show only the service name and username.
 * [onOpenCredential] opens the login screen; a brand-new entry gets a fresh id and is only
 * stored when it is saved.
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

    var itemToDelete by remember { mutableStateOf<CredentialListItem?>(null) }
    var itemToMove by remember { mutableStateOf<CredentialListItem?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }
    var showGenerator by remember { mutableStateOf(false) }

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

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader("Passwords")
            Spacer(Modifier.height(16.dp))
            SearchField(state = searchState, hint = "Search website")
            Spacer(Modifier.height(12.dp))
            ChipRow(
                favoritesOnly = state.favoritesOnly,
                canCreateFolder = state.isBrowsing,
                onFavoritesOnlyChange = viewModel::setFavoritesOnly,
                onNewFolder = { creatingFolder = true },
                onGenerator = { showGenerator = true },
                onHealth = onOpenHealth
            )
            if (state.isBrowsing && state.currentFolder != null) {
                PathRow(path = state.path, onNavigate = openFolder)
            } else {
                Spacer(Modifier.height(4.dp))
            }

            val isEmpty = state.items.isEmpty() && state.folders.isEmpty()
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
                            title = "No matching logins",
                            message = "Search looks at website addresses only. Try a different search or filter."
                        )
                        state.currentFolder != null -> EmptyState(
                            icon = TazIcons.Folder,
                            title = "This folder is empty",
                            message = "Add a login here, or create a folder inside it."
                        )
                        else -> EmptyState(
                            icon = TazIcons.Lock,
                            title = "No passwords yet",
                            message = "Keep your logins in one private place. They are encrypted and never leave this device."
                        )
                    }
                }
                else -> PasswordsList(
                    folders = state.folders,
                    items = state.items,
                    onOpenFolder = { openFolder(it.id) },
                    onOpenItem = onOpenCredential,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onMoveItem = { itemToMove = it },
                    onDeleteItem = { itemToDelete = it },
                    onRenameFolder = { folderToRename = it },
                    onDeleteFolder = { folderToDelete = it },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        TazFab(
            label = "Add password",
            onClick = { onOpenCredential(UUID.randomUUID().toString()) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }

    if (showGenerator) {
        PasswordGeneratorDialog(onDismiss = { showGenerator = false })
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
                        "Logins inside are kept and moved to the top level."
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

    itemToMove?.let { item ->
        MoveToFolderDialog(
            folders = state.allFolders,
            currentFolderId = item.folderId,
            onSelect = { folderId ->
                viewModel.moveCredential(item.id, folderId)
                itemToMove = null
            },
            onDismiss = { itemToMove = null }
        )
    }

    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Move login to trash?") },
            text = { Text("\"${item.serviceName}\" will be moved to the trash. You can restore it for 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCredential(item.id)
                    itemToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ChipRow(
    favoritesOnly: Boolean,
    canCreateFolder: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onNewFolder: () -> Unit,
    onGenerator: () -> Unit,
    onHealth: () -> Unit
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
        if (canCreateFolder) {
            AssistChip(onClick = onNewFolder, label = { Text("New folder") })
        }
        AssistChip(onClick = onGenerator, label = { Text("Generator") })
        AssistChip(onClick = onHealth, label = { Text("Health") })
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
        PathSegment(text = "Passwords", isCurrent = false) { onNavigate(null) }
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
private fun PasswordsList(
    folders: List<FolderEntity>,
    items: List<CredentialListItem>,
    onOpenFolder: (FolderEntity) -> Unit,
    onOpenItem: (String) -> Unit,
    onToggleFavorite: (CredentialListItem) -> Unit,
    onMoveItem: (CredentialListItem) -> Unit,
    onDeleteItem: (CredentialListItem) -> Unit,
    onRenameFolder: (FolderEntity) -> Unit,
    onDeleteFolder: (FolderEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        if (folders.isNotEmpty()) {
            item(key = "label-folders") { ListLabel("Folders") }
            items(folders, key = { "folder-${it.id}" }) { folder ->
                FolderRow(folder, onOpenFolder, onRenameFolder, onDeleteFolder, Modifier.animateItem())
            }
        }
        if (items.isNotEmpty()) {
            if (folders.isNotEmpty()) {
                item(key = "label-logins") { ListLabel("Logins") }
            }
            items(items, key = { it.id }) { item ->
                CredentialRow(item, onOpenItem, onToggleFavorite, onMoveItem, onDeleteItem, Modifier.animateItem())
            }
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
private fun FolderRow(
    folder: FolderEntity,
    onOpen: (FolderEntity) -> Unit,
    onRename: (FolderEntity) -> Unit,
    onDelete: (FolderEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onOpen(folder) },
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
                    onRename(folder)
                }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    onDelete(folder)
                }
            )
        }
    }
}

/** A saved login. Shows the service name and username only: never the password or notes. */
@Composable
private fun CredentialRow(
    item: CredentialListItem,
    onOpen: (String) -> Unit,
    onToggleFavorite: (CredentialListItem) -> Unit,
    onMove: (CredentialListItem) -> Unit,
    onDelete: (CredentialListItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onOpen(item.id) },
                        onLongClick = { menuOpen = true }
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.serviceName,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (item.username.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.username,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (item.isFavorite) {
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
                text = { Text(if (item.isFavorite) "Remove from favorites" else "Add to favorites") },
                onClick = {
                    menuOpen = false
                    onToggleFavorite(item)
                }
            )
            DropdownMenuItem(
                text = { Text("Move to folder") },
                onClick = {
                    menuOpen = false
                    onMove(item)
                }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    onDelete(item)
                }
            )
        }
    }
}
