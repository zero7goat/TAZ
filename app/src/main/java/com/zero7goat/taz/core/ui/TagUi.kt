package com.zero7goat.taz.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.tags.Tag
import com.zero7goat.taz.core.tags.TagNames
import kotlinx.coroutines.launch

/** One compact tag. With [onRemove] it shows a small close mark (used while editing). */
@Composable
fun TagChip(name: String, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = if (onRemove != null) 4.dp else 8.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(28.dp)
                        .clickable(role = Role.Button, onClickLabel = "Remove tag $name", onClick = onRemove),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(TazIcons.Close, contentDescription = "Remove tag $name", modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/**
 * Tags of a list row on a single line: the first [max] and "+N" for the rest, so rows never grow
 * taller because of tags.
 */
@Composable
fun TagChipLine(tags: List<Tag>, modifier: Modifier = Modifier, max: Int = 3) {
    if (tags.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tags.take(max).forEach { TagChip(it.name, Modifier.weight(1f, fill = false)) }
        if (tags.size > max) {
            Text(
                text = "+${tags.size - max}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/**
 * The "Tags" section of an item: its chips (removable when [editable]) and an Add button that opens
 * the picker. Used by notes, logins and files.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsSection(
    tags: List<Tag>,
    editable: Boolean,
    onEdit: () -> Unit,
    onRemove: (Tag) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().animateContentSize(tween(160))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                TazIcons.Tag,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Tags",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
            if (editable) {
                TextButton(onClick = onEdit) { Text(if (tags.isEmpty()) "Add" else "Edit") }
            }
        }
        if (tags.isEmpty()) {
            if (!editable) {
                Text(
                    text = "None",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tags.forEach { tag ->
                    TagChip(tag.name, onRemove = if (editable) ({ onRemove(tag) }) else null)
                }
            }
        }
    }
}

/**
 * Picks tags for one or several items. Has a search field (useful with many tags), shows the
 * selected ones first, and can create a tag from the search text. Creating "work" when "Work"
 * exists selects the existing tag instead of making a duplicate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(
    allTags: List<Tag>,
    initiallySelected: Set<String>,
    onCreate: suspend (String) -> Tag?,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Tags"
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val query = rememberTextFieldState()
    var selected by remember { mutableStateOf(initiallySelected) }
    // Tags created in this sheet show up at once, before the database flow catches up.
    var created by remember { mutableStateOf(emptyList<Tag>()) }
    val known = (allTags + created).distinctBy { it.id }

    val text = query.text.toString()
    val key = TagNames.normalize(text)
    val visible = known
        .filter { key.isEmpty() || TagNames.normalize(it.name).contains(key) }
        .sortedWith(compareByDescending<Tag> { it.id in initiallySelected }.thenBy { it.name.lowercase() })
    val exactExists = key.isNotEmpty() && known.any { TagNames.normalize(it.name) == key }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Button(onClick = { onDone(selected) }) { Text("Done") }
            }
            Spacer(Modifier.size(12.dp))
            SearchField(state = query, hint = "Search or create a tag")
            Spacer(Modifier.size(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (key.isNotEmpty() && !exactExists) {
                    item(key = "create") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val tag = onCreate(text) ?: return@launch
                                        created = created + tag
                                        selected = selected + tag.id
                                        query.replaceAllText("")
                                    }
                                }
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(TazIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = "Create \"${TagNames.clean(text)}\"",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                items(visible, key = { it.id }) { tag ->
                    val isSelected = tag.id in selected
                    Row(
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) {
                                selected = if (isSelected) selected - tag.id else selected + tag.id
                            }
                            .heightIn(min = 52.dp)
                            .padding(start = 8.dp, end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = isSelected, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                        Text(tag.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (visible.isEmpty() && key.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "No tags yet. Type a name above to create one.",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            AnimatedVisibility(visible = selected != initiallySelected, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text = "${selected.size} selected",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}
