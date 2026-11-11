package com.zero7goat.taz.features.search.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.database.FolderEntity
import com.zero7goat.taz.core.tags.Tag
import com.zero7goat.taz.core.ui.EmptyState
import com.zero7goat.taz.core.ui.ListSectionLabel
import com.zero7goat.taz.core.ui.TagChipLine
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.core.ui.folderPathLabel
import com.zero7goat.taz.features.files.presentation.fileIconFor
import com.zero7goat.taz.features.notes.presentation.noteColor
import com.zero7goat.taz.features.passwords.domain.Categories
import com.zero7goat.taz.features.passwords.presentation.CategoryBadge
import com.zero7goat.taz.features.search.data.SearchIndex
import com.zero7goat.taz.features.search.domain.DateRange
import com.zero7goat.taz.features.search.domain.ResultType
import com.zero7goat.taz.features.search.domain.SearchFilters
import com.zero7goat.taz.features.search.domain.SearchHit
import com.zero7goat.taz.features.search.domain.VaultSearch

/**
 * Searches the whole vault: notes, logins, files and folders, with filters for type, favorites,
 * tags, folder and date. Results are grouped by type, matches are highlighted, and opening one
 * goes to the same screen as opening it from its list. Passwords and hidden values are never
 * searched or shown.
 */
@Composable
fun SearchScreen(
    onClose: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenLogin: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenFolder: (FolderEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(LocalContext.current.applicationContext))
    val results = remember(viewModel) { viewModel.results() }
    val initial = remember(viewModel) { viewModel.initialState() }
    val state by results.collectAsStateWithLifecycle(initial)
    val queryState = rememberTextFieldState(remember(viewModel) { viewModel.currentQuery() })
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var showFilters by remember { mutableStateOf(false) }

    LaunchedEffect(queryState) { snapshotFlow { queryState.text.toString() }.collect { viewModel.setQuery(it) } }
    LaunchedEffect(Unit) { if (queryState.text.isEmpty()) focus.requestFocus() }
    BackHandler(onBack = onClose)

    Column(modifier = modifier.systemBarsPadding().imePadding()) {
        // Search bar: back, the query, and a clear button.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(TazIcons.ChevronLeft, contentDescription = "Back") }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(modifier = Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(TazIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (queryState.text.isEmpty()) {
                            Text("Search everything", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        }
                        BasicTextField(
                            state = queryState,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            onKeyboardAction = { focusManager.clearFocus() }
                        )
                    }
                    if (queryState.text.isNotEmpty()) {
                        IconButton(onClick = { queryState.edit { replace(0, length, "") } }) {
                            Icon(TazIcons.Close, contentDescription = "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Type chips and the filters button.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val counts = state.countsByType
            val showCounts = !state.isIdle && !state.isLoading
            FilterChip(
                selected = state.filters.types.isEmpty(),
                onClick = { viewModel.clearTypes() },
                label = { Text(if (showCounts) "All ${counts.values.sum()}" else "All") }
            )
            ResultType.entries.forEach { type ->
                FilterChip(
                    selected = type in state.filters.types,
                    onClick = { viewModel.toggleType(type) },
                    label = { Text(if (showCounts) "${type.label} ${counts[type] ?: 0}" else type.label) }
                )
            }
            FilterChip(
                selected = state.filters.extraCount > 0,
                onClick = { showFilters = true },
                label = { Text("Filters") },
                leadingIcon = { Icon(TazIcons.Filter, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                trailingIcon = if (state.filters.extraCount > 0) {
                    { Badge { Text("${state.filters.extraCount}") } }
                } else {
                    null
                }
            )
        }
        ActiveFilterSummary(state, onReset = { viewModel.resetFilters() })

        AnimatedVisibility(visible = state.isLoading, enter = fadeIn(), exit = fadeOut()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }

        val content = when {
            state.isLoading -> SearchContent.LOADING
            state.isIdle -> SearchContent.IDLE
            state.hits.isEmpty() -> SearchContent.EMPTY
            else -> SearchContent.RESULTS
        }
        AnimatedContent(
            targetState = content,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
            label = "search-content"
        ) { shown ->
            when (shown) {
                SearchContent.LOADING -> Box(Modifier.fillMaxSize())
                SearchContent.IDLE -> CenteredState {
                    EmptyState(
                        icon = TazIcons.Search,
                        title = "Search your whole vault",
                        message = "Find notes, logins, files and folders by name, text, username, website, " +
                            "keyword or tag. Every word you type must match. Passwords are never searched."
                    )
                }
                SearchContent.EMPTY -> CenteredState {
                    EmptyState(
                        icon = TazIcons.Search,
                        title = "No matches",
                        message = if (state.filters.isActive) "Try fewer words or clear the filters." else "Try fewer or different words.",
                        actionLabel = if (state.filters.isActive) "Clear filters" else null,
                        onAction = {
                            viewModel.resetFilters()
                            viewModel.clearTypes()
                        }
                    )
                }
                SearchContent.RESULTS -> ResultsList(
                    state = state,
                    onOpen = { hit ->
                        focusManager.clearFocus()
                        when (hit.doc.type) {
                            ResultType.NOTE -> onOpenNote(hit.doc.id)
                            ResultType.LOGIN -> onOpenLogin(hit.doc.id)
                            ResultType.FILE -> onOpenFile(hit.doc.id)
                            ResultType.FOLDER -> state.index?.folders?.firstOrNull { it.id == hit.doc.id }?.let(onOpenFolder)
                        }
                    }
                )
            }
        }
    }

    val index = state.index
    if (showFilters && index != null) {
        FiltersSheet(
            index = index,
            filters = state.filters,
            onChange = { transform -> viewModel.setFilters(transform) },
            onReset = { viewModel.resetFilters() },
            onDismiss = { showFilters = false }
        )
    }
}

private enum class SearchContent { LOADING, IDLE, EMPTY, RESULTS }

@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.Center) { content() }
}

/** One line naming the active non-type filters, with a quick way to clear them. */
@Composable
private fun ActiveFilterSummary(state: SearchUiState, onReset: () -> Unit) {
    val filters = state.filters
    val index = state.index
    val parts = buildList {
        if (filters.favoritesOnly) add("Favorites")
        if (filters.tagIds.isNotEmpty() && index != null) {
            add(index.tags.filter { it.id in filters.tagIds }.joinToString(", ") { "#${it.name}" })
        }
        if (filters.folderId != null && index != null) {
            index.folders.firstOrNull { it.id == filters.folderId }?.let { add("In ${it.name}") }
        }
        if (filters.modified != DateRange.ANY) add(filters.modified.label)
    }
    AnimatedVisibility(visible = parts.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = parts.joinToString(" · "),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onReset) { Text("Clear") }
        }
    }
}

@Composable
private fun ResultsList(state: SearchUiState, onOpen: (SearchHit) -> Unit) {
    val index = state.index ?: return
    val grouped = state.hits.groupBy { it.doc.type }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        ResultType.entries.forEach { type ->
            val hits = grouped[type].orEmpty()
            if (hits.isNotEmpty()) {
                item(key = "header-$type") {
                    Box(Modifier.animateItem().semantics { heading() }) { ListSectionLabel("${type.label} · ${hits.size}") }
                }
                items(hits, key = { "${type}:${it.doc.id}" }) { hit ->
                    ResultRow(hit = hit, query = state.query, index = index, onClick = { onOpen(hit) }, modifier = Modifier.animateItem())
                }
            }
        }
        item(key = "end") { Spacer(Modifier.size(32.dp).navigationBarsPadding()) }
    }
}

@Composable
private fun ResultRow(hit: SearchHit, query: String, index: SearchIndex, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val doc = hit.doc
    val highlight = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.SemiBold
    )
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ResultIcon(hit, index)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = highlighted(doc.title, query, highlight),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = hit.snippet
                if (detail != null && hit.matchedLabel != null) {
                    Text(
                        text = buildAnnotatedString {
                            append("${hit.matchedLabel}: ")
                            append(highlighted(detail.removePrefix("${hit.matchedLabel}: "), query, highlight))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (doc.summary.isNotBlank()) {
                    Text(
                        text = doc.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val location = locationOf(doc.type, doc.folderId, index)
                if (location != null || doc.tagNames.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (location != null) {
                            Icon(TazIcons.Folder, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                location,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        TagChipLine(doc.tagNames.mapIndexed { i, name -> Tag("$i", name) }, max = 2)
                    }
                }
            }
            if (doc.isFavorite) {
                Icon(TazIcons.StarFilled, contentDescription = "Favorite", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ResultIcon(hit: SearchHit, index: SearchIndex) {
    val doc = hit.doc
    when (doc.type) {
        ResultType.LOGIN -> CategoryBadge(Categories.resolve(doc.hint, index.categories).icon, size = 40.dp)
        else -> {
            val icon: ImageVector = when (doc.type) {
                ResultType.NOTE -> TazIcons.Notes
                ResultType.FILE -> fileIconFor(doc.hint, doc.title)
                else -> TazIcons.Folder
            }
            val accent = if (doc.type == ResultType.NOTE) noteColor(doc.hint.toIntOrNull() ?: 0) else null
            Box(
                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = accent ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun locationOf(type: ResultType, folderId: String?, index: SearchIndex): String? {
    if (folderId == null) return null
    val root = when (type) {
        ResultType.NOTE -> "Notes"
        ResultType.LOGIN -> "Passwords"
        ResultType.FILE -> "Files"
        ResultType.FOLDER -> ""
    }
    val folder = index.folders.firstOrNull { it.id == folderId } ?: return null
    val sameModule = index.folders.filter { it.module == folder.module }
    return folderPathLabel(sameModule, folderId, root).ifBlank { null }
}

private fun highlighted(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    VaultSearch.highlightRanges(text, query).forEach { range -> addStyle(style, range.first, range.last + 1) }
}

/** Favorites, tags, folder and date. Changes apply right away; the sheet only gathers them. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FiltersSheet(
    index: SearchIndex,
    filters: SearchFilters,
    onChange: ((SearchFilters) -> SearchFilters) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tagQuery by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Row(modifier = Modifier.padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                TextButton(onClick = onReset, enabled = filters.extraCount > 0) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) { onChange { it.copy(favoritesOnly = !it.favoritesOnly) } }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(TazIcons.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text("Favorites only", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = filters.favoritesOnly, onCheckedChange = null)
            }

            ListSectionLabel("Modified")
            FlowRow(modifier = Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateRange.entries.forEach { range ->
                    FilterChip(
                        selected = filters.modified == range,
                        onClick = { onChange { it.copy(modified = range) } },
                        label = { Text(range.label) }
                    )
                }
            }

            ListSectionLabel("Tags")
            if (index.tags.isEmpty()) {
                Text(
                    "No tags yet.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                if (index.tags.size > 12) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            if (tagQuery.isEmpty()) Text("Find a tag", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            BasicTextField(
                                value = tagQuery,
                                onValueChange = { tagQuery = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                                keyboardActions = KeyboardActions.Default,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    index.tags
                        .filter { tagQuery.isBlank() || it.name.contains(tagQuery.trim(), ignoreCase = true) || it.id in filters.tagIds }
                        .forEach { tag ->
                            val selected = tag.id in filters.tagIds
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    onChange { f -> f.copy(tagIds = if (selected) f.tagIds - tag.id else f.tagIds + tag.id) }
                                },
                                label = { Text(tag.name) },
                                leadingIcon = { Icon(TazIcons.Tag, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            )
                        }
                }
                if (filters.tagIds.size > 1) {
                    Text(
                        "Items must have all selected tags.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            ListSectionLabel("Folder")
            FolderChoice(
                label = "Any folder",
                selected = filters.folderId == null,
                depth = 0,
                onClick = { onChange { it.copy(folderId = null) } }
            )
            listOf("NOTES" to "Notes", "PASSWORDS" to "Passwords", "FILES" to "Files").forEach { (module, name) ->
                val inModule = index.folders.filter { it.module == module }
                if (inModule.isNotEmpty()) {
                    Text(
                        name,
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium
                    )
                    folderTree(inModule).forEach { (folder, depth) ->
                        FolderChoice(
                            label = folder.name,
                            selected = filters.folderId == folder.id,
                            depth = depth,
                            onClick = { onChange { it.copy(folderId = folder.id) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderChoice(label: String, selected: Boolean, depth: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = 20.dp + (depth * 16).dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(TazIcons.Folder, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(TazIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

/** Folders in tree order with their depth (parents before children, alphabetical siblings). */
private fun folderTree(folders: List<FolderEntity>): List<Pair<FolderEntity, Int>> {
    val ids = folders.mapTo(HashSet()) { it.id }
    val byParent = folders.groupBy { it.parentId?.takeIf { p -> p in ids } }
    val result = ArrayList<Pair<FolderEntity, Int>>()
    fun visit(parent: String?, depth: Int) {
        if (depth > 32) return
        byParent[parent].orEmpty().sortedBy { it.name.lowercase() }.forEach {
            result += it to depth
            visit(it.id, depth + 1)
        }
    }
    visit(null, 0)
    return result
}
