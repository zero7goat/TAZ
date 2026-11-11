package com.zero7goat.taz.core.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.CustomFields
import com.zero7goat.taz.core.fields.FieldType
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

private const val MASK = "••••••••"

fun FieldType.icon(): ImageVector = when (this) {
    FieldType.TEXT -> TazIcons.TextLines
    FieldType.MULTILINE -> TazIcons.Notes
    FieldType.NUMBER -> TazIcons.Hash
    FieldType.DATE -> TazIcons.Calendar
    FieldType.URL -> TazIcons.Globe
    FieldType.EMAIL -> TazIcons.Mail
    FieldType.PHONE -> TazIcons.Phone
    FieldType.SECRET -> TazIcons.Key
    FieldType.TOGGLE -> TazIcons.Toggle
}

/** A date stored as ISO text, shown in the device's format. Unparseable text is shown as it is. */
fun formatFieldDate(value: String): String =
    CustomFields.parseDate(value)?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: value

/** What a field shows when it is not being edited. Hidden values stay masked until revealed. */
fun displayValue(field: CustomField, revealed: Boolean): String = when {
    field.isHidden && !revealed && field.value.isNotEmpty() -> MASK
    field.type == FieldType.TOGGLE -> if (field.value == CustomFields.TRUE) "Yes" else "No"
    field.type == FieldType.DATE -> formatFieldDate(field.value)
    else -> field.value
}

/**
 * Read-only custom fields: label, value (hidden values masked, with an eye to reveal), and a copy
 * button. Copying a hidden value goes through [onCopy], which uses the sensitive clipboard.
 */
@Composable
fun CustomFieldsView(
    fields: List<CustomField>,
    revealed: Set<String>,
    onToggleReveal: (String) -> Unit,
    onCopy: (CustomField) -> Unit,
    modifier: Modifier = Modifier,
    onOpenUrl: ((String) -> Unit)? = null
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        fields.forEach { field ->
            key(field.id) {
                val isRevealed = field.id in revealed
                Text(
                    text = field.label.ifBlank { field.type.label },
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium
                )
                FieldSurface {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayValue(field, isRevealed).ifEmpty { "Not set" },
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                            color = if (field.value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = if (field.isHidden) FontFamily.Monospace else FontFamily.Default
                            )
                        )
                        if (field.isHidden && field.value.isNotEmpty()) {
                            IconButton(onClick = { onToggleReveal(field.id) }) {
                                Icon(
                                    if (isRevealed) TazIcons.EyeOff else TazIcons.Eye,
                                    contentDescription = if (isRevealed) "Hide ${field.label}" else "Show ${field.label}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (field.type == FieldType.URL && onOpenUrl != null && field.value.isNotBlank()) {
                            IconButton(onClick = { onOpenUrl(field.value) }) {
                                Icon(TazIcons.OpenExternal, contentDescription = "Open ${field.label}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (field.value.isNotEmpty() && field.type != FieldType.TOGGLE) {
                            IconButton(onClick = { onCopy(field) }) {
                                Icon(TazIcons.Copy, contentDescription = "Copy ${field.label}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Editable custom fields. Each field has a label, a value input that fits its type (keyboard,
 * masking, date picker, switch) and a menu to change type, hide the value, move or delete it.
 * [onGenerate] fills a secret field with a generated password (null hides the button).
 */
@Composable
fun CustomFieldsEditor(
    fields: List<CustomField>,
    onChange: (List<CustomField>) -> Unit,
    revealed: Set<String>,
    onToggleReveal: (String) -> Unit,
    modifier: Modifier = Modifier,
    onGenerate: ((String) -> Unit)? = null
) {
    fun update(id: String, transform: (CustomField) -> CustomField) =
        onChange(fields.map { if (it.id == id) transform(it) else it })

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in fields.indices) return
        onChange(fields.toMutableList().also { list -> list.add(target, list.removeAt(index)) })
    }

    Column(modifier = modifier.animateContentSize(tween(180))) {
        fields.forEachIndexed { index, field ->
            key(field.id) {
                FieldEditorCard(
                    field = field,
                    revealed = field.id in revealed,
                    canMoveUp = index > 0,
                    canMoveDown = index < fields.lastIndex,
                    onChange = { changed -> update(field.id) { changed } },
                    onToggleReveal = { onToggleReveal(field.id) },
                    onMoveUp = { move(index, -1) },
                    onMoveDown = { move(index, 1) },
                    onDelete = { onChange(fields.filterNot { it.id == field.id }) },
                    onGenerate = onGenerate?.let { generate -> { generate(field.id) } }
                )
            }
        }
        if (fields.size < CustomFields.MAX_FIELDS) {
            AddFieldButton(onAdd = { type ->
                onChange(fields + CustomField(UUID.randomUUID().toString(), "", type, if (type == FieldType.TOGGLE) CustomFields.FALSE else ""))
            })
        }
    }
}

@Composable
private fun AddFieldButton(onAdd: (FieldType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(top = 12.dp)) {
        TextButton(onClick = { open = true }) {
            Icon(TazIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add field")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FieldType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.label) },
                    leadingIcon = { Icon(type.icon(), contentDescription = null) },
                    onClick = {
                        open = false
                        onAdd(type)
                    }
                )
            }
        }
    }
}

@Composable
private fun FieldEditorCard(
    field: CustomField,
    revealed: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (CustomField) -> Unit,
    onToggleReveal: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onGenerate: (() -> Unit)?
) {
    var menuOpen by remember { mutableStateOf(false) }
    var typeMenuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 14.dp)) {
        // Label row: the label is editable inline; the menu holds everything else.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(field.type.icon(), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (field.label.isEmpty()) {
                    Text("Field name", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                }
                BasicTextField(
                    value = field.label,
                    onValueChange = { onChange(field.copy(label = it.take(CustomFields.MAX_LABEL_LENGTH))) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(TazIcons.More, contentDescription = "Options for ${field.label.ifBlank { "this field" }}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Type: ${field.type.label}") },
                        leadingIcon = { Icon(TazIcons.Category, contentDescription = null) },
                        onClick = { menuOpen = false; typeMenuOpen = true }
                    )
                    if (field.type != FieldType.SECRET && field.type != FieldType.TOGGLE) {
                        DropdownMenuItem(
                            text = { Text(if (field.isHidden) "Show value normally" else "Hide value") },
                            leadingIcon = { Icon(if (field.isHidden) TazIcons.Eye else TazIcons.EyeOff, contentDescription = null) },
                            onClick = { menuOpen = false; onChange(field.copy(hidden = !field.isHidden)) }
                        )
                    }
                    if (canMoveUp) {
                        DropdownMenuItem(
                            text = { Text("Move up") },
                            leadingIcon = { Icon(TazIcons.ArrowUp, contentDescription = null) },
                            onClick = { menuOpen = false; onMoveUp() }
                        )
                    }
                    if (canMoveDown) {
                        DropdownMenuItem(
                            text = { Text("Move down") },
                            leadingIcon = { Icon(TazIcons.ArrowDown, contentDescription = null) },
                            onClick = { menuOpen = false; onMoveDown() }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete field") },
                        leadingIcon = { Icon(TazIcons.Trash, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
                DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                    FieldType.entries.forEach { type ->
                        val allowed = CustomFields.canChangeType(field, type)
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(type.label)
                                    if (!allowed) {
                                        Text(
                                            "The current value doesn't fit",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            leadingIcon = { Icon(type.icon(), contentDescription = null) },
                            trailingIcon = if (type == field.type) ({ Icon(TazIcons.Check, contentDescription = "Current type") }) else null,
                            enabled = allowed,
                            onClick = {
                                typeMenuOpen = false
                                if (type != field.type) {
                                    val value = if (type == FieldType.TOGGLE && field.value.isEmpty()) CustomFields.FALSE else field.value
                                    onChange(field.copy(type = type, value = value, hidden = field.isHidden || type == FieldType.SECRET))
                                }
                            }
                        )
                    }
                }
            }
        }
        FieldValueInput(field, revealed, onChange, onToggleReveal, onGenerate)
        if (!CustomFields.isValidValue(field.type, field.value)) {
            Text(
                text = when (field.type) {
                    FieldType.NUMBER -> "Enter digits only (for example 1234 or 12.5)."
                    FieldType.DATE -> "Pick a date."
                    else -> "This value doesn't fit the field type."
                },
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldValueInput(
    field: CustomField,
    revealed: Boolean,
    onChange: (CustomField) -> Unit,
    onToggleReveal: () -> Unit,
    onGenerate: (() -> Unit)?
) {
    when (field.type) {
        FieldType.TOGGLE -> FieldSurface {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) {
                        onChange(field.copy(value = if (field.value == CustomFields.TRUE) CustomFields.FALSE else CustomFields.TRUE))
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (field.value == CustomFields.TRUE) "Yes" else "No", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = field.value == CustomFields.TRUE, onCheckedChange = null)
            }
        }

        FieldType.DATE -> {
            var picking by remember { mutableStateOf(false) }
            FieldSurface(innerEndPadding = 2.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (field.value.isEmpty()) "No date" else displayValue(field, revealed),
                        modifier = Modifier
                            .weight(1f)
                            .clickable(role = Role.Button) { picking = true }
                            .padding(vertical = 4.dp),
                        color = if (field.value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (field.value.isNotEmpty()) {
                        IconButton(onClick = { onChange(field.copy(value = "")) }) {
                            Icon(TazIcons.Close, contentDescription = "Clear date", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { picking = true }) {
                        Icon(TazIcons.Calendar, contentDescription = "Pick a date", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (picking) {
                val initial = CustomFields.parseDate(field.value)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
                val state = rememberDatePickerState(initialSelectedDateMillis = initial)
                DatePickerDialog(
                    onDismissRequest = { picking = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { millis ->
                                val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                                onChange(field.copy(value = date.toString()))
                            }
                            picking = false
                        }) { Text("OK") }
                    },
                    dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
                ) { DatePicker(state = state) }
            }
        }

        else -> {
            val masked = field.isHidden && !revealed
            val keyboard = when (field.type) {
                FieldType.NUMBER -> if (field.isHidden) KeyboardType.NumberPassword else KeyboardType.Decimal
                FieldType.URL -> KeyboardType.Uri
                FieldType.EMAIL -> KeyboardType.Email
                FieldType.PHONE -> KeyboardType.Phone
                FieldType.SECRET -> KeyboardType.Password
                else -> if (field.isHidden) KeyboardType.Password else KeyboardType.Text
            }
            val multiline = field.type == FieldType.MULTILINE && !masked
            FieldSurface(innerEndPadding = if (field.isHidden || onGenerate != null) 2.dp else 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = field.value,
                        onValueChange = { onChange(field.copy(value = it.take(CustomFields.MAX_VALUE_LENGTH))) },
                        modifier = Modifier.weight(1f).heightIn(min = if (multiline) 72.dp else 0.dp),
                        singleLine = !multiline,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = if (field.isHidden) FontFamily.Monospace else FontFamily.Default
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboard,
                            autoCorrectEnabled = !field.isHidden && field.type in setOf(FieldType.TEXT, FieldType.MULTILINE),
                            capitalization = if (field.type == FieldType.TEXT || field.type == FieldType.MULTILINE) {
                                KeyboardCapitalization.Sentences
                            } else {
                                KeyboardCapitalization.None
                            }
                        )
                    )
                    if (field.type == FieldType.SECRET && onGenerate != null) {
                        IconButton(onClick = onGenerate) {
                            Icon(TazIcons.Generate, contentDescription = "Generate a password", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (field.isHidden) {
                        IconButton(onClick = onToggleReveal) {
                            Icon(
                                if (revealed) TazIcons.EyeOff else TazIcons.Eye,
                                contentDescription = if (revealed) "Hide value" else "Show value",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The rounded field background used by the login and note forms. */
@Composable
fun FieldSurface(
    modifier: Modifier = Modifier,
    innerEndPadding: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(start = 14.dp, end = innerEndPadding, top = 6.dp, bottom = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) { content() }
    }
}
