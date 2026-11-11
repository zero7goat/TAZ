package com.zero7goat.taz.features.passwords.presentation

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.fields.FieldType
import com.zero7goat.taz.core.ui.ListSectionLabel
import com.zero7goat.taz.core.ui.ScreenHeader
import com.zero7goat.taz.core.ui.TazFab
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.core.ui.icon
import com.zero7goat.taz.features.passwords.data.CategoryRepository
import com.zero7goat.taz.features.passwords.domain.Categories
import com.zero7goat.taz.features.passwords.domain.CategoryIcon
import com.zero7goat.taz.features.passwords.domain.FieldTemplate
import com.zero7goat.taz.features.passwords.domain.LoginCategory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoriesViewModel(private val repository: CategoryRepository) : ViewModel() {

    val custom: StateFlow<List<LoginCategory>> =
        repository.observeCustom().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun save(key: String?, name: String, icon: CategoryIcon, template: List<FieldTemplate>): String? =
        repository.save(key, name, icon, template)

    fun delete(key: String) {
        viewModelScope.launch { repository.delete(key) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer { CategoriesViewModel(CategoryRepository(TazDatabase.getInstance(appContext).categoryDao())) }
            }
        }
    }
}

/**
 * The built-in categories (read-only) and the user's own, which can be created, edited and deleted.
 * A custom category is a name, an icon and the fields new entries start with. Deleting one keeps
 * its entries and their fields; they become "Other".
 */
@Composable
fun CategoriesScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: CategoriesViewModel = viewModel(factory = CategoriesViewModel.factory(LocalContext.current.applicationContext))
    val custom by viewModel.custom.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<LoginCategory?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LoginCategory?>(null) }

    BackHandler(onBack = onClose)

    Box(modifier = modifier.systemBarsPadding()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                    IconButton(onClick = onClose) { Icon(TazIcons.ChevronLeft, contentDescription = "Back") }
                }
                ScreenHeader("Login categories")
                Text(
                    text = "A category decides which fields a new entry starts with. You can always add or remove fields on any entry.",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item { ListSectionLabel("Your categories") }
            if (custom.isEmpty()) {
                item {
                    Text(
                        text = "None yet. Tap New category to make one, for example \"Membership\" or \"Server\".",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(custom, key = { it.key }) { category ->
                CategoryRow(
                    category = category,
                    onClick = { editing = category },
                    onDelete = { deleting = category },
                    modifier = Modifier.animateItem()
                )
            }
            item { ListSectionLabel("Built in") }
            items(Categories.builtIn, key = { it.key }) { category ->
                CategoryRow(category = category, onClick = null, onDelete = null)
            }
            item { Spacer(Modifier.size(96.dp)) }
        }
        TazFab(
            label = "New category",
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }

    val scope = rememberCoroutineScope()
    if (creating || editing != null) {
        CategoryEditorDialog(
            initial = editing,
            onSave = { name, icon, template ->
                val key = editing?.key
                scope.launch {
                    viewModel.save(key, name, icon, template)
                    creating = false
                    editing = null
                }
            },
            onDismiss = {
                creating = false
                editing = null
            }
        )
    }

    deleting?.let { category ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete category?") },
            text = { Text("Entries in \"${category.name}\" are kept with all their fields and move to Other.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(category.key)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CategoryRow(category: LoginCategory, onClick: (() -> Unit)?, onDelete: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick, onClickLabel = "Edit") else Modifier)
                .heightIn(min = 64.dp)
                .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryBadge(category.icon)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(category.name, style = MaterialTheme.typography.titleMedium)
                val fields = when {
                    category.usesLoginFields -> "Username, password, website"
                    category.template.isEmpty() -> "Name and notes"
                    else -> category.template.joinToString(", ") { it.label }
                }
                Text(
                    fields,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(TazIcons.Trash, contentDescription = "Delete ${category.name}") }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Name, icon and template fields (label and type) of a custom category. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorDialog(
    initial: LoginCategory?,
    onSave: (String, CategoryIcon, List<FieldTemplate>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var icon by remember { mutableStateOf(initial?.icon ?: CategoryIcon.OTHER) }
    var fields by remember { mutableStateOf(initial?.template ?: listOf(FieldTemplate("", FieldType.TEXT))) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New category" else "Edit category") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Icon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryIcon.choices.forEach { choice ->
                        val selected = choice == icon
                        Surface(
                            onClick = { icon = choice },
                            shape = CircleShape,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(choice.vector(), contentDescription = choice.key, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
                Text("Fields new entries start with", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                fields.forEachIndexed { index, template ->
                    TemplateFieldRow(
                        template = template,
                        onChange = { changed -> fields = fields.toMutableList().also { it[index] = changed } },
                        onDelete = { fields = fields.toMutableList().also { it.removeAt(index) } }
                    )
                }
                TextButton(onClick = { fields = fields + FieldTemplate("", FieldType.TEXT) }) {
                    Icon(TazIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add field")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, icon, fields) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TemplateFieldRow(template: FieldTemplate, onChange: (FieldTemplate) -> Unit, onDelete: () -> Unit) {
    var typeOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = template.label,
            onValueChange = { onChange(template.copy(label = it.take(60))) },
            label = { Text("Field name") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(onClick = { typeOpen = true }) {
                Icon(template.type.icon(), contentDescription = "Type: ${template.type.label}")
            }
            DropdownMenu(expanded = typeOpen, onDismissRequest = { typeOpen = false }) {
                FieldType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label) },
                        leadingIcon = { Icon(type.icon(), contentDescription = null) },
                        onClick = {
                            typeOpen = false
                            onChange(template.copy(type = type, hidden = type == FieldType.SECRET))
                        }
                    )
                }
            }
        }
        IconButton(onClick = onDelete) { Icon(TazIcons.Close, contentDescription = "Remove field") }
    }
}
