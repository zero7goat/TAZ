@file:OptIn(ExperimentalFoundationApi::class)

package com.zero7goat.taz.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zero7goat.taz.core.database.FolderEntity

/**
 * Lists, selection and the toolbar shared by Notes, Passwords and Files, so the three screens
 * look and behave the same way.
 *
 * Selection works like other Android apps: long-press an item to start selecting, tap to add or
 * remove items, and use the bar at the top for the selected items. Tapping an item outside
 * selection mode opens it.
 */

/** One extra action in the selection bar's overflow menu. */
class SelectionAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The top of a list screen: the title (with [actions]) normally, and the selection bar while
 * [selectedCount] is above zero. The two cross-fade quickly, without moving the list below.
 */
@Composable
fun CollectionHeader(
    title: String,
    selectedCount: Int,
    allSelected: Boolean,
    onClearSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onMoveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    moreSelectionActions: List<SelectionAction> = emptyList(),
    actions: @Composable RowScope.() -> Unit = {}
) {
    AnimatedContent(
        targetState = selectedCount > 0,
        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
        label = "header"
    ) { selecting ->
        if (selecting) {
            SelectionBar(
                count = selectedCount,
                allSelected = allSelected,
                onClose = onClearSelection,
                onToggleSelectAll = onToggleSelectAll,
                onMove = onMoveSelected,
                onDelete = onDeleteSelected,
                more = moreSelectionActions
            )
        } else {
            val openSearch = LocalOpenSearch.current
            ScreenHeader(title = title) {
                if (openSearch != null) {
                    IconButton(onClick = openSearch) {
                        Icon(TazIcons.Search, contentDescription = "Search everything")
                    }
                }
                actions()
            }
        }
    }
}

/** Opens the vault-wide search. Provided by the app shell; null where search is not offered. */
val LocalOpenSearch = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    more: List<SelectionAction>
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Same height as the title header, so the list does not jump when selection starts.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 16.dp)
            .height(90.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Row(modifier = Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(TazIcons.Close, contentDescription = "Cancel selection") }
            AnimatedContent(targetState = count, label = "count") { n ->
                Text(
                    text = "$n selected",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleSelectAll) {
                Icon(TazIcons.SelectAll, contentDescription = if (allSelected) "Deselect all" else "Select all")
            }
            IconButton(onClick = onMove) { Icon(TazIcons.FolderMove, contentDescription = "Move to folder") }
            IconButton(onClick = onDelete) { Icon(TazIcons.Trash, contentDescription = "Move to trash") }
            if (more.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(TazIcons.More, contentDescription = "More actions") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        more.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label) },
                                enabled = action.enabled,
                                onClick = {
                                    menuOpen = false
                                    action.onClick()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Filters and folder creation under the search field. [extra] holds screen-specific chips (for
 * example Files' sort and filter).
 */
@Composable
fun CollectionToolbar(
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    canCreateFolder: Boolean,
    onNewFolder: () -> Unit,
    extra: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = favoritesOnly,
            onClick = { onFavoritesOnlyChange(!favoritesOnly) },
            label = { Text("Favorites") },
            leadingIcon = {
                Icon(
                    imageVector = if (favoritesOnly) TazIcons.StarFilled else TazIcons.Star,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        )
        extra()
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible = canCreateFolder, enter = fadeIn(), exit = fadeOut()) {
            TextButton(onClick = onNewFolder) {
                Icon(TazIcons.FolderPlus, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New folder")
            }
        }
    }
}

/** Upper-case section label inside a list ("FOLDERS", "PINNED"). */
@Composable
fun ListSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    )
}

/**
 * A list row that can be opened or selected. In selection mode a tap toggles it; a long-press
 * starts selection. The background tint and the check mark animate briefly.
 */
@Composable
fun SelectableRow(
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onToggleSelected: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    leading: @Composable () -> Unit = {},
    content: @Composable RowScope.() -> Unit
) {
    val background by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.surfaceContainerHighest
            tint != null -> tint
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "row-background"
    )
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelected() else onOpen() },
                    onLongClick = onToggleSelected,
                    onLongClickLabel = if (selected) "Deselect" else "Select",
                    role = if (selectionMode) Role.Checkbox else Role.Button
                )
                .semantics { if (selectionMode) stateDescription = if (selected) "Selected" else "Not selected" }
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(80)) },
                label = "row-leading"
            ) { selecting ->
                if (selecting) SelectionMark(selected) else leading()
            }
            content()
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/** The round check shown in front of each item while selecting. */
@Composable
private fun SelectionMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .padding(end = 14.dp)
            .size(24.dp)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(visible = selected, enter = scaleIn(tween(120)), exit = scaleOut(tween(90))) {
            Icon(
                imageVector = TazIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/**
 * A folder in a list. Tapping opens it; a long-press shows Rename and Delete. While items are
 * being selected, folders are dimmed and inactive (folders themselves are not selectable).
 */
@Composable
fun FolderListRow(
    folder: FolderEntity,
    subtitle: String?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier.alpha(if (enabled) 1f else 0.45f)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = enabled,
                        onClick = onOpen,
                        onLongClick = { menuOpen = true },
                        onLongClickLabel = "Folder options"
                    )
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = TazIcons.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(
                        imageVector = TazIcons.More,
                        contentDescription = "Options for ${folder.name}",
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
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
            DropdownMenuItem(text = { Text("Delete folder") }, onClick = { menuOpen = false; onDelete() })
        }
    }
}

/** A small trailing status icon in a row (pinned, favorite). */
@Composable
fun RowStatusIcon(icon: ImageVector, description: String) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        modifier = Modifier
            .padding(start = 10.dp)
            .size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Confirms moving [count] selected items to the trash. Nothing is erased: they can be restored. */
@Composable
fun BulkTrashDialog(count: Int, noun: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val subject = if (count == 1) "1 $noun" else "$count ${noun}s"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move $subject to trash?") },
        text = { Text("You can restore ${if (count == 1) "it" else "them"} from Settings → Trash for 30 days.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Move to trash") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Keeps a selection limited to items that are still on screen: when the list changes (search,
 * filter, another folder, an item deleted elsewhere) anything no longer visible drops out.
 */
fun Set<String>.keepVisible(visibleIds: Collection<String>): Set<String> {
    if (isEmpty()) return this
    val visible = visibleIds.toHashSet()
    return if (all { it in visible }) this else filterTo(LinkedHashSet()) { it in visible }
}

/** The selected item ids, kept across rotation and process recreation (ids only, never content). */
@Composable
fun rememberSelection(): MutableState<Set<String>> =
    rememberSaveable(
        saver = Saver<MutableState<Set<String>>, ArrayList<String>>(
            save = { ArrayList(it.value) },
            restore = { mutableStateOf(it.toSet()) }
        )
    ) { mutableStateOf(emptySet()) }
