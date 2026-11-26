package com.zero7goat.taz.features.notes.presentation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.reminders.Reminders
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.notes.data.NoteVersionEntity
import com.zero7goat.taz.features.notes.domain.NoteTemplate
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

/** The ⋮ menu of the note editor: extra lock, reminder, version history and templates. */
@Composable
fun NoteMoreMenu(
    lockAvailable: Boolean,
    isLocked: Boolean,
    remindAt: Long?,
    onToggleLock: () -> Unit,
    onPickReminder: () -> Unit,
    onClearReminder: () -> Unit,
    onHistory: () -> Unit,
    onSaveTemplate: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(TazIcons.More, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (lockAvailable) {
                DropdownMenuItem(
                    text = { Text(if (isLocked) "Stop asking for passcode" else "Ask for passcode to open") },
                    leadingIcon = { Icon(TazIcons.Lock, contentDescription = null) },
                    onClick = { open = false; onToggleLock() }
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        if (remindAt == null) "Remind me…"
                        else "Reminder: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(remindAt))
                    )
                },
                leadingIcon = { Icon(TazIcons.Calendar, contentDescription = null) },
                onClick = { open = false; onPickReminder() }
            )
            if (remindAt != null) {
                DropdownMenuItem(
                    text = { Text("Remove reminder") },
                    leadingIcon = { Icon(TazIcons.Close, contentDescription = null) },
                    onClick = { open = false; onClearReminder() }
                )
            }
            DropdownMenuItem(
                text = { Text("Version history") },
                leadingIcon = { Icon(TazIcons.Restore, contentDescription = null) },
                onClick = { open = false; onHistory() }
            )
            DropdownMenuItem(
                text = { Text("Save as template") },
                leadingIcon = { Icon(TazIcons.Notes, contentDescription = null) },
                onClick = { open = false; onSaveTemplate() }
            )
        }
    }
}

/**
 * Picks a date, then a time, for a note reminder. Asks for the notification permission
 * (Android 13+) the first time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderPicker(initial: Long?, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val start = initial?.let { Instant.ofEpochMilli(it).atZone(zone) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> denied = !granted }
    val needsPermission = !Reminders.canNotify(context) && Build.VERSION.SDK_INT >= 33

    if (date == null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (start?.toLocalDate() ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    if (needsPermission) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    } else {
        val time = rememberTimePickerState(start?.hour ?: 9, start?.minute ?: 0)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Time") },
            text = {
                Column {
                    TimePicker(state = time)
                    if (denied) {
                        Text(
                            "Notifications are off for TAZ, so the reminder can't be shown. You can allow them in Android settings.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val at = LocalDateTime.of(date, LocalTime.of(time.hour, time.minute)).atZone(zone).toInstant().toEpochMilli()
                    onPicked(at)
                }) { Text("Set reminder") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }
}

/** Earlier versions of a note. Tap one to see it and bring it back. */
@Composable
fun NoteHistoryDialog(
    versions: List<NoteVersionEntity>,
    onRestore: (NoteVersionEntity) -> Unit,
    onDelete: (NoteVersionEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var viewing by remember { mutableStateOf<NoteVersionEntity?>(null) }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val shown = viewing
    if (shown == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Version history") },
            text = {
                if (versions.isEmpty()) {
                    Text(
                        "No earlier versions yet. TAZ keeps one about every 10 minutes while you edit, up to 20 per note.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(versions, key = { it.id }) { version ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewing = version }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(format.format(Date(version.savedAt)), style = MaterialTheme.typography.labelLarge)
                                Text(
                                    version.title.ifBlank { version.body.take(80) }.ifBlank { "(empty)" },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(format.format(Date(shown.savedAt))) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.title.isNotBlank()) Text(shown.title, style = MaterialTheme.typography.titleMedium)
                    if (shown.description.isNotBlank()) Text(shown.description, style = MaterialTheme.typography.bodyMedium)
                    Text(shown.body, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { onRestore(shown); viewing = null }) { Text("Restore this version") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onDelete(shown); viewing = null }) { Text("Delete") }
                    TextButton(onClick = { viewing = null }) { Text("Back") }
                }
            }
        )
    }
}

/** Template chips shown on a new, empty note. */
@Composable
fun TemplateChips(templates: List<NoteTemplate>, onPick: (NoteTemplate) -> Unit) {
    if (templates.isEmpty()) return
    Column(Modifier.padding(top = 12.dp)) {
        Text("Start from a template", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            templates.forEach { template ->
                AssistChip(onClick = { onPick(template) }, label = { Text(template.name) })
            }
        }
    }
}

/** Names a new template made from the current note. */
@Composable
fun SaveTemplateDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New notes can start from this note's title and text.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Template name") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
