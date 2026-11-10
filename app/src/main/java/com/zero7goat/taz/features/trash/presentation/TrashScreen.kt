package com.zero7goat.taz.features.trash.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.ui.EmptyState
import com.zero7goat.taz.core.ui.ScreenHeader
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.trash.domain.TrashItem
import com.zero7goat.taz.features.trash.domain.TrashKind
import com.zero7goat.taz.features.trash.domain.TrashRules

/**
 * Deleted notes, logins and files. Items can be restored for [TrashRules.RETENTION_DAYS] days;
 * after that they are removed for good. Nothing secret is shown here: logins appear by name only.
 */
@Composable
fun TrashScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: TrashViewModel = viewModel(factory = TrashViewModel.factory(LocalContext.current.applicationContext))
    val items by viewModel.items.collectAsStateWithLifecycle()
    var confirmEmpty by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<TrashItem?>(null) }
    val now = remember(items) { System.currentTimeMillis() }

    BackHandler(onBack = onClose)

    Column(modifier = modifier.systemBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(imageVector = TazIcons.ChevronLeft, contentDescription = "Back")
            }
            Spacer(Modifier.weight(1f))
            if (!items.isNullOrEmpty()) {
                TextButton(onClick = { confirmEmpty = true }) { Text("Empty trash") }
            }
        }
        ScreenHeader("Trash")
        Text(
            text = "Deleted items stay here for ${TrashRules.RETENTION_DAYS} days, then are removed for good.",
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        val list = items
        when {
            list == null -> Box(Modifier.weight(1f))
            list.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = TazIcons.Trash,
                    title = "Trash is empty",
                    message = "Notes, logins and files you delete show up here so you can bring them back."
                )
            }
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxSize()) {
                items(list, key = { "${it.kind}:${it.id}" }) { item ->
                    TrashRow(
                        item = item,
                        now = now,
                        onRestore = { viewModel.restore(item) },
                        onDeleteForever = { itemToDelete = item }
                    )
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty trash?") },
            text = { Text("Everything in the trash will be deleted for good. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    viewModel.emptyTrash()
                }) { Text("Empty trash") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } }
        )
    }

    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete for good?") },
            text = { Text("\"${item.displayLabel()}\" will be deleted permanently. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    itemToDelete = null
                    viewModel.deleteForever(item)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { itemToDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TrashRow(item: TrashItem, now: Long, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    val daysLeft = TrashRules.daysLeft(item.deletedAt, now)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.displayLabel(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${item.kind.label()} · ${daysLeftText(daysLeft)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onRestore) { Text("Restore") }
            TextButton(onClick = onDeleteForever) { Text("Delete") }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

private fun TrashItem.displayLabel(): String = label.ifBlank { "Untitled" }

private fun TrashKind.label(): String = when (this) {
    TrashKind.NOTE -> "Note"
    TrashKind.LOGIN -> "Login"
    TrashKind.FILE -> "File"
}

private fun daysLeftText(days: Int): String = when (days) {
    0 -> "Deleting soon"
    1 -> "1 day left"
    else -> "$days days left"
}
