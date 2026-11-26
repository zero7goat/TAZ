package com.zero7goat.taz.features.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.security.UnlockResult
import com.zero7goat.taz.core.transfer.ImportPreview
import com.zero7goat.taz.core.ui.PinField
import kotlinx.coroutines.launch

/** Explains what can be imported before the file picker opens. */
@Composable
fun ImportInfoDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import logins") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Bring your logins from another app. Export them there first, then pick the file here:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• Chrome, Edge, Brave, Firefox (CSV)\n• Bitwarden (CSV or unencrypted JSON)\n" +
                        "• KeePass / KeePassXC, 1Password, Proton Pass, Dashlane (CSV)\n• Most other CSV files with a password column",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "You'll see what was found before anything is saved. Logins already in TAZ are skipped. " +
                        "Folders, favourites, tags and two-factor keys come along when the file has them.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Choose file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** What was found in the file. Nothing is saved until the user confirms. */
@Composable
fun ImportPreviewDialog(preview: ImportPreview, onImport: () -> Unit, onDismiss: () -> Unit) {
    val total = preview.logins.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import ${preview.newCount} logins?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Found $total ${if (total == 1) "login" else "logins"} in this ${preview.format} file.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (preview.duplicates > 0) {
                    Text(
                        "${preview.duplicates} ${if (preview.duplicates == 1) "is" else "are"} already in TAZ and will be skipped.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (preview.skipped > 0) {
                    Text(
                        "${preview.skipped} other ${if (preview.skipped == 1) "entry" else "entries"} (such as cards or notes) can't be imported.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (preview.newCount == 0) {
                    Text("There is nothing new to import.", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        },
        confirmButton = { TextButton(onClick = onImport, enabled = preview.newCount > 0) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The warning before an unencrypted CSV export. When the app lock is on, the passcode is asked
 * again, so an unlocked phone left on a table can't be used to dump every password.
 */
@Composable
fun ExportCsvDialog(
    lockEnabled: Boolean,
    onVerify: suspend (CharArray) -> UnlockResult,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pin = remember { TextFieldState() }
    var understood by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Export logins to CSV") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Creates a CSV file with every login, including usernames, passwords, notes, custom fields " +
                        "and two-factor keys, in a layout Bitwarden, Proton Pass, KeePassXC and most other apps can import.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "The file is NOT encrypted. Anyone who gets it can read all your passwords. Import it right " +
                        "away and then delete it. To keep a copy, use an encrypted backup instead.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.error
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = understood, onCheckedChange = null)
                    Spacer(Modifier.size(8.dp))
                    Text("I understand the file is not protected", style = MaterialTheme.typography.bodyMedium)
                }
                if (lockEnabled) PinField(state = pin, label = "Passcode")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = understood && !busy,
                onClick = {
                    if (!lockEnabled) {
                        onContinue()
                        return@TextButton
                    }
                    val entered = pin.text.toString().toCharArray()
                    pin.edit { replace(0, length, "") }
                    if (entered.isEmpty()) {
                        error = "Enter your passcode."
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        val result = onVerify(entered)
                        busy = false
                        if (result == UnlockResult.Success) onContinue() else error = result.failureText()
                    }
                }
            ) { Text("Choose location") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}
