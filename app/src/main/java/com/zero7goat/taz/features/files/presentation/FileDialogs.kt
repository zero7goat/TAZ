package com.zero7goat.taz.features.files.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.features.files.data.FileEntity
import com.zero7goat.taz.features.files.domain.DateFilter
import com.zero7goat.taz.features.files.domain.FileCategory
import com.zero7goat.taz.features.files.domain.FileSort
import com.zero7goat.taz.features.files.domain.FilesQuery
import com.zero7goat.taz.features.files.domain.SizeFilter
import com.zero7goat.taz.features.files.domain.keywordList

/** Edit a file's name, keywords and description. */
@Composable
fun EditFileDetailsDialog(
    file: FileEntity,
    onSave: (name: String, keywords: String, description: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(file.id) { mutableStateOf(file.displayName) }
    var keywords by remember(file.id) { mutableStateOf(keywordList(file.keywords).joinToString(", ")) }
    var description by remember(file.id) { mutableStateOf(file.description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit details") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Keywords") },
                    placeholder = { Text("work, invoice, 2024") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description") },
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, keywords, description) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Sort order and filters for the file list. Changes apply immediately. */
@Composable
fun SortFilterDialog(
    query: FilesQuery,
    onSort: (FileSort) -> Unit,
    onType: (FileCategory?) -> Unit,
    onSize: (SizeFilter) -> Unit,
    onDate: (DateFilter) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort and filter") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("Sort by")
                FileSort.entries.forEach { sort ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSort(sort) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = query.sort == sort, onClick = { onSort(sort) })
                        Text(sort.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                SectionTitle("Type")
                DialogChipRow {
                    FilterChip(
                        selected = query.typeFilter == null,
                        onClick = { onType(null) },
                        label = { Text("Any") }
                    )
                    FileCategory.entries.forEach { category ->
                        FilterChip(
                            selected = query.typeFilter == category,
                            onClick = { onType(category) },
                            label = { Text(category.label) }
                        )
                    }
                }

                SectionTitle("Size")
                DialogChipRow {
                    SizeFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = query.sizeFilter == filter,
                            onClick = { onSize(filter) },
                            label = { Text(filter.label) }
                        )
                    }
                }

                SectionTitle("Date added")
                DialogChipRow {
                    DateFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = query.dateFilter == filter,
                            onClick = { onDate(filter) },
                            label = { Text(filter.label) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onReset) { Text("Reset") } }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

@Composable
private fun DialogChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}
