package com.zero7goat.taz.features.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.security.UnlockResult
import com.zero7goat.taz.core.settings.AppDisguise
import com.zero7goat.taz.core.ui.PinField
import kotlinx.coroutines.launch

/**
 * Sets up the duress passcode: a second passcode that opens an empty decoy vault instead of the
 * real one, for when someone forces you to unlock TAZ. The real passcode is asked first.
 */
@Composable
fun DuressPasscodeDialog(
    hasDuress: Boolean,
    onSet: suspend (current: CharArray, duress: CharArray) -> UnlockResult,
    onRemove: suspend () -> Unit,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val current = remember { TextFieldState() }
    val duress = remember { TextFieldState() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Duress passcode") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "A second passcode for when someone makes you unlock TAZ. It opens a separate, empty vault " +
                        "that looks like the normal app. Your real notes, logins and files stay hidden and untouched. " +
                        "Lock TAZ and enter your real passcode to get back.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Fingerprint and face unlock always open your real vault, so consider turning them off if this matters to you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PinField(state = current, label = "Your passcode")
                PinField(state = duress, label = if (hasDuress) "New duress passcode" else "Duress passcode")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (hasDuress) {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            onRemove()
                            busy = false
                            onDone("Duress passcode removed")
                        }
                    }) { Text("Remove duress passcode") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val a = current.text.toString().toCharArray()
                val b = duress.text.toString().toCharArray()
                current.edit { replace(0, length, "") }
                duress.edit { replace(0, length, "") }
                busy = true
                scope.launch {
                    val result = onSet(a, b)
                    busy = false
                    when (result) {
                        UnlockResult.Success -> onDone("Duress passcode set")
                        is UnlockResult.Wrong -> error = "Check your passcode. The duress passcode must be 4 to 16 digits and differ from it."
                        else -> error = result.failureText()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

/** Chooses after how many wrong passcodes in a row TAZ erases everything (or never). */
@Composable
fun WipeAfterDialog(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(0 to "Never", 10 to "After 10 wrong passcodes", 15 to "After 15 wrong passcodes", 20 to "After 20 wrong passcodes")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Erase after wrong passcodes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Erases all TAZ data on this phone after that many wrong passcodes in a row. This can't be undone; only a backup brings your data back.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.error
                )
                options.forEach { (count, label) ->
                    RadioRow(label, selected = current == count) { onSelect(count) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Chooses the home-screen icon and name. */
@Composable
fun AppIconDialog(current: AppDisguise, onSelect: (AppDisguise) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App icon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Show TAZ on the home screen as another app. Everything inside stays the same, and your passcode still protects it. " +
                        "The launcher may take a few seconds to update.",
                    style = MaterialTheme.typography.bodyMedium
                )
                AppDisguise.entries.forEach { option ->
                    RadioRow(if (option == AppDisguise.NONE) "TAZ (normal)" else option.label, selected = current == option) { onSelect(option) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** How many days ahead expiry reminders come, and when passwords count as old. */
@Composable
fun RemindersDialog(expiryDays: Int, passwordMonths: Int, onExpiry: (Int) -> Unit, onPasswords: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminders") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Shown as notifications, worked out on this phone once a day. Their details are hidden on the lock screen.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("Expiry dates (cards, IDs, licences)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
                listOf(0 to "Off", 7 to "A week before", 30 to "A month before", 60 to "Two months before").forEach { (d, label) ->
                    RadioRow(label, selected = expiryDays == d) { onExpiry(d) }
                }
                Spacer(Modifier.size(8.dp))
                Text("Old passwords", style = MaterialTheme.typography.labelLarge)
                listOf(0 to "Off", 6 to "Older than 6 months", 12 to "Older than a year", 24 to "Older than 2 years").forEach { (m, label) ->
                    RadioRow(label, selected = passwordMonths == m) { onPasswords(m) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
