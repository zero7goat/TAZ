package com.zero7goat.taz.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.database.FolderEntity
import kotlinx.coroutines.launch

/**
 * Folder helpers shared by Notes, Passwords and Files, so all three behave the same way:
 * one name dialog, one delete confirmation, one breadcrumb row and one folder picker.
 */

/** "Work / Projects" style path of [folderId] in [folders], or [rootLabel] for the top level. */
fun folderPathLabel(folders: List<FolderEntity>, folderId: String?, rootLabel: String): String {
    if (folderId == null) return rootLabel
    val byId = folders.associateBy { it.id }
    val names = ArrayDeque<String>()
    var node = byId[folderId]
    val seen = HashSet<String>()
    while (node != null && seen.add(node.id) && seen.size <= 64) {
        names.addFirst(node.name)
        node = node.parentId?.let { byId[it] }
    }
    return if (names.isEmpty()) rootLabel else names.joinToString(" / ")
}

/**
 * Asks for a folder name, for creating or renaming. The field is focused with the keyboard open,
 * and the keyboard's Done key confirms. [location] says where a new folder will appear.
 */
@Composable
fun FolderNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    location: String? = null,
    fieldLabel: String = "Folder name",
    error: String? = null
) {
    var value by remember {
        mutableStateOf(TextFieldValue(initialName, selection = TextRange(0, initialName.length)))
    }
    val focus = remember { FocusRequester() }
    val canConfirm = value.text.isNotBlank()
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (location != null) {
                    Text(
                        text = "In $location",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    label = { Text(fieldLabel) },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(value.text.trim()) })
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = canConfirm) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Confirms deleting a folder. Nothing inside is deleted: subfolders and [itemsNoun] move up to
 * [destination] (the parent folder, or the top level).
 */
@Composable
fun DeleteFolderDialog(
    folderName: String,
    itemsNoun: String,
    destination: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"$folderName\"?") },
        text = {
            Text(
                "Only the folder is removed. Its $itemsNoun and subfolders move to $destination, " +
                    "so nothing inside is lost."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Breadcrumbs for the folder being browsed. Every level except the current one is tappable. */
@Composable
fun FolderPathRow(rootLabel: String, path: List<FolderEntity>, onNavigate: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PathSegment(text = rootLabel, isCurrent = path.isEmpty()) { onNavigate(null) }
        path.forEachIndexed { index, folder ->
            Icon(
                imageVector = TazIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            PathSegment(text = folder.name, isCurrent = index == path.lastIndex) { onNavigate(folder.id) }
        }
    }
}

@Composable
private fun PathSegment(text: String, isCurrent: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !isCurrent, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
        )
    )
}

/**
 * Picks where to move [itemCount] items. The sheet starts in the folder the items are in now (when
 * they share one), lets the user open folders and go back up, create a folder on the spot, and
 * always shows the exact destination on the button, so it is never unclear where things will go.
 *
 * [currentFolderIds] are the folders the items are in now; moving to the only one of them is
 * disabled because it would change nothing. [onCreateFolder] creates a folder and returns its id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(
    rootLabel: String,
    itemNoun: String,
    itemCount: Int,
    folders: List<FolderEntity>,
    currentFolderIds: Set<String?>,
    onMove: (String?) -> Unit,
    onCreateFolder: suspend (name: String, parentId: String?) -> String?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val byId = remember(folders) { folders.associateBy { it.id } }
    val start = currentFolderIds.singleOrNull()?.takeIf { it in byId }
    var browsingId by rememberSaveable { mutableStateOf(start) }
    // A folder deleted while the sheet is open falls back to the top level.
    val browsing = browsingId?.takeIf { it in byId }
    var creating by remember { mutableStateOf(false) }

    val destinationLabel = folderPathLabel(folders, browsing, rootLabel)
    val unchanged = currentFolderIds.size == 1 && currentFolderIds.first() == browsing
    val subject = if (itemCount == 1) "1 $itemNoun" else "$itemCount ${itemNoun}s"

    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = "Move $subject",
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge
            )
            if (currentFolderIds.size == 1) {
                Text(
                    text = "Now in ${folderPathLabel(folders, currentFolderIds.first(), rootLabel)}",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.size(8.dp))
            FolderPathRow(
                rootLabel = rootLabel,
                path = generateSequence(browsing?.let { byId[it] }) { f -> f.parentId?.let { byId[it] } }
                    .toList().asReversed(),
                onNavigate = { browsingId = it }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            AnimatedContent(
                targetState = browsing,
                transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                label = "picker-folder"
            ) { shownFolderId ->
                // Each side of the cross-fade lists its own folder's subfolders.
                val shown = remember(folders, shownFolderId) {
                    folders.filter { it.parentId == shownFolderId }.sortedBy { it.name.lowercase() }
                }
                LazyColumn(modifier = Modifier.heightIn(min = 120.dp, max = 340.dp)) {
                    items(shown, key = { it.id }) { folder ->
                        PickerRow(
                            title = folder.name,
                            subtitle = folders.count { it.parentId == folder.id }
                                .takeIf { it > 0 }?.let { if (it == 1) "1 folder" else "$it folders" },
                            onClick = { browsingId = folder.id }
                        )
                    }
                    item(key = "new-folder") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { creating = true }
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(TazIcons.FolderPlus, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = "New folder here",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { dismissThen(onDismiss) }) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { dismissThen { onMove(browsing) } }, enabled = !unchanged) {
                    Text(
                        text = if (unchanged) "Already here" else "Move to $destinationLabel",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (creating) {
        FolderNameDialog(
            title = "New folder",
            initialName = "",
            confirmLabel = "Create",
            location = destinationLabel,
            onConfirm = { name ->
                creating = false
                scope.launch { onCreateFolder(name, browsing)?.let { browsingId = it } }
            },
            onDismiss = { creating = false }
        )
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(TazIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Icon(
            imageVector = TazIcons.ChevronRight,
            contentDescription = "Open",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.outline
        )
    }
}
