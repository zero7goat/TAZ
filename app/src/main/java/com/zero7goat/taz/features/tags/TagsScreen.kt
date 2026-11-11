package com.zero7goat.taz.features.tags

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.tags.TagResult
import com.zero7goat.taz.core.tags.TagUsageRow
import com.zero7goat.taz.core.ui.EmptyState
import com.zero7goat.taz.core.ui.FolderNameDialog
import com.zero7goat.taz.core.ui.ScreenHeader
import com.zero7goat.taz.core.ui.TazFab
import com.zero7goat.taz.core.ui.TazIcons
import kotlinx.coroutines.launch

/**
 * All tags, with how many items use each. Tags can be created, renamed and deleted here; deleting a
 * tag only removes it from items, never the items themselves.
 */
@Composable
fun TagsScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: TagsViewModel = viewModel(factory = TagsViewModel.factory(LocalContext.current.applicationContext))
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<TagUsageRow?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<TagUsageRow?>(null) }

    BackHandler(onBack = onClose)

    Box(modifier = modifier.systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                IconButton(onClick = onClose) { Icon(TazIcons.ChevronLeft, contentDescription = "Back") }
            }
            ScreenHeader("Tags")
            Text(
                text = "Tags group notes, logins and files across folders. Deleting a tag never deletes the items.",
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            AnimatedContent(
                targetState = usage?.isEmpty(),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                label = "tags-content"
            ) { empty ->
                when (empty) {
                    null -> Box(Modifier.fillMaxSize())
                    true -> Box(Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = TazIcons.Tag,
                            title = "No tags yet",
                            message = "Create a tag here, or add one while editing a note, login or file."
                        )
                    }
                    false -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(usage.orEmpty(), key = { it.id }) { tag ->
                            TagRow(
                                tag = tag,
                                onRename = { renameError = null; renaming = tag },
                                onDelete = { deleting = tag },
                                modifier = Modifier.animateItem()
                            )
                        }
                        item { Spacer(Modifier.size(96.dp)) }
                    }
                }
            }
        }
        TazFab(
            label = "New tag",
            onClick = { createError = null; creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }

    if (creating) {
        FolderNameDialog(
            title = "New tag",
            initialName = "",
            confirmLabel = "Create",
            fieldLabel = "Tag name",
            error = createError,
            onConfirm = { name ->
                scope.launch {
                    val known = viewModel.usage.value.orEmpty()
                    val key = com.zero7goat.taz.core.tags.TagNames.normalize(name)
                    if (known.any { it.normalizedName == key }) {
                        createError = "A tag with this name already exists"
                    } else {
                        viewModel.createOrGet(name)
                        creating = false
                    }
                }
            },
            onDismiss = { creating = false }
        )
    }

    renaming?.let { tag ->
        FolderNameDialog(
            title = "Rename tag",
            initialName = tag.name,
            confirmLabel = "Rename",
            fieldLabel = "Tag name",
            error = renameError,
            onConfirm = { name ->
                scope.launch {
                    when (val result = viewModel.rename(tag.id, name)) {
                        is TagResult.Duplicate -> renameError = "\"${result.existing.name}\" already exists"
                        TagResult.Blank -> renameError = "Enter a name"
                        is TagResult.Ok -> renaming = null
                    }
                }
            },
            onDismiss = { renaming = null }
        )
    }

    deleting?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete tag?") },
            text = {
                Text(
                    if (tag.usage == 0) {
                        "\"${tag.name}\" isn't used by any item."
                    } else {
                        "\"${tag.name}\" will be removed from ${tag.usage} ${if (tag.usage == 1) "item" else "items"}. " +
                            "The items themselves are kept."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(tag.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TagRow(tag: TagUsageRow, onRename: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRename, onClickLabel = "Rename")
                .heightIn(min = 60.dp)
                .padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(TazIcons.Tag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tag.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = when (tag.usage) {
                        0 -> "Not used"
                        1 -> "1 item"
                        else -> "${tag.usage} items"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onRename) { Icon(TazIcons.Edit, contentDescription = "Rename ${tag.name}") }
            IconButton(onClick = onDelete) { Icon(TazIcons.Trash, contentDescription = "Delete ${tag.name}") }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
