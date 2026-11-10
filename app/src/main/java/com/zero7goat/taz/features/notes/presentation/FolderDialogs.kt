package com.zero7goat.taz.features.notes.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.database.FolderEntity

/** Asks for a folder name. Used for both creating and renaming. */
@Composable
fun FolderNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Lets the user pick a destination folder, or the top level, for a note. */
@Composable
fun MoveToFolderDialog(
    folders: List<FolderEntity>,
    currentFolderId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val options = remember(folders) { folderLabels(folders) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                item(key = "top-level") {
                    MoveOption("No folder", isCurrent = currentFolderId == null) { onSelect(null) }
                }
                items(options, key = { it.first }) { (id, label) ->
                    MoveOption(label, isCurrent = id == currentFolderId) { onSelect(id) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MoveOption(label: String, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge
        )
        if (isCurrent) {
            Text(
                text = "Current",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** Folder ids with a "Parent / Child" style label, sorted by label. */
private fun folderLabels(folders: List<FolderEntity>): List<Pair<String, String>> {
    val byId = folders.associateBy { it.id }
    return folders
        .map { folder ->
            val names = ArrayDeque<String>()
            var node: FolderEntity? = folder
            val visited = mutableSetOf<String>()
            while (node != null && visited.add(node.id) && visited.size < 32) {
                names.addFirst(node.name)
                node = node.parentId?.let { byId[it] }
            }
            folder.id to names.joinToString(" / ")
        }
        .sortedBy { it.second.lowercase() }
}
