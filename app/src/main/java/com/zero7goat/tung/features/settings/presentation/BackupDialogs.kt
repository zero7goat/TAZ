package com.zero7goat.tung.features.settings.presentation

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.zero7goat.tung.core.backup.BackupError
import com.zero7goat.tung.core.backup.BackupSummary
import com.zero7goat.tung.core.backup.RestoreSummary

/** Progress and outcome of a backup, restore or export. */
sealed interface TransferState {
    data object Idle : TransferState
    data class Working(val message: String) : TransferState
    data class Finished(val title: String, val message: String) : TransferState
    data class Failed(val title: String, val message: String) : TransferState
}

/** Minimum length of a backup passphrase. */
const val MIN_PASSPHRASE_LENGTH = 10

internal fun backupMessage(summary: BackupSummary): String = buildString {
    append("Saved ${summary.notes} notes, ${summary.logins} logins, ${summary.files} files and ")
    append("${summary.folders} folders, encrypted with your passphrase.")
    if (summary.missingFiles > 0 || summary.missingNoteImages > 0) {
        append(" ${summary.missingFiles + summary.missingNoteImages} stored files or images were missing on this device and are not included.")
    }
    if (summary.skippedLogins > 0) {
        append(" ${summary.skippedLogins} logins could not be decrypted on this device and are not included.")
    }
    append(" Keep the passphrase safe: without it the backup cannot be opened.")
}

internal fun restoreMessage(summary: RestoreSummary): String = buildString {
    append("Restored ${summary.notes} notes, ${summary.logins} logins, ${summary.files} files and ${summary.folders} folders.")
    if (summary.missingFiles > 0) {
        append(" ${summary.missingFiles} files were already missing when the backup was made; their details are kept.")
    }
    if (summary.skippedLogins > 0) {
        append(" ${summary.skippedLogins} logins were unreadable on the original device and were not in the backup.")
    }
}

internal fun failureTitle(error: BackupError): String = when (error) {
    BackupError.WRONG_PASSPHRASE -> "Wrong passphrase"
    BackupError.NOT_A_BACKUP -> "Not a TAZ backup"
    BackupError.UNSUPPORTED_VERSION -> "Unsupported backup"
    BackupError.NOT_ENOUGH_STORAGE -> "Not enough storage"
    else -> "Couldn't complete"
}

internal fun failureMessage(error: BackupError): String {
    val unchanged = "Nothing on this device was changed."
    return when (error) {
        BackupError.WRONG_PASSPHRASE -> "The passphrase doesn't match this backup, or the file is damaged. $unchanged"
        BackupError.NOT_A_BACKUP -> "This file isn't a TAZ backup. $unchanged"
        BackupError.UNSUPPORTED_VERSION -> "This backup was made by a newer or different version of TAZ. $unchanged"
        BackupError.DAMAGED, BackupError.INVALID_CONTENT -> "The backup file is damaged or has been altered. $unchanged"
        BackupError.INCOMPLETE -> "The backup file is incomplete. It may not have been copied fully. $unchanged"
        BackupError.TOO_LARGE -> "The backup is too large to process. $unchanged"
        BackupError.NOT_ENOUGH_STORAGE -> "There isn't enough free storage. Free some space and try again. $unchanged"
        BackupError.CANNOT_READ -> "The file couldn't be read. $unchanged"
        BackupError.CANNOT_WRITE -> "The backup couldn't be written to the chosen location. Your data was not changed."
        BackupError.FAILED -> "Something went wrong. $unchanged"
    }
}

/** Asks for a new passphrase (twice) before the user picks where to save a backup. */
@Composable
fun BackupPassphraseDialog(
    passphrase: TextFieldState,
    confirm: TextFieldState,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = passphrase.text.toString()
    val tooShort = first.length < MIN_PASSPHRASE_LENGTH
    val mismatch = confirm.text.isNotEmpty() && confirm.text.toString() != first
    val ready = !tooShort && confirm.text.toString() == first
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Back up to a file") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "The backup holds all your notes and their images, logins (including passwords), " +
                        "stored files, folders and app preferences. It does not include your app lock passcode.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Everything is encrypted with the passphrase below. You will need it to restore, also on " +
                        "another device. TAZ does not store it and cannot recover it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedSecureTextField(
                    state = passphrase,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Passphrase") },
                    supportingText = { Text("At least $MIN_PASSPHRASE_LENGTH characters") }
                )
                OutlinedSecureTextField(
                    state = confirm,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Repeat passphrase") },
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text("Passphrases don't match") }) else null
                )
            }
        },
        confirmButton = { TextButton(onClick = onContinue, enabled = ready) { Text("Choose location") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Confirms replacing everything with a backup and asks for its passphrase. */
@Composable
fun RestoreConfirmDialog(passphrase: TextFieldState, onRestore: () -> Unit, onDismiss: () -> Unit) {
    var confirmed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from backup?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "All notes, logins, files, folders and preferences currently in TAZ will be replaced by " +
                        "the contents of the backup. Consider making a backup of your current data first.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "The backup is checked completely before anything is replaced. If it can't be opened or " +
                        "is damaged, your current data stays as it is. Your app lock passcode is not changed.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedSecureTextField(
                    state = passphrase,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Backup passphrase") }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = confirmed, role = Role.Checkbox, onValueChange = { confirmed = it }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = confirmed, onCheckedChange = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Replace my current data", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRestore, enabled = confirmed && passphrase.text.isNotEmpty()) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Explains what the spreadsheet export contains before the user picks where to save it. */
@Composable
fun ExportInfoDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export to spreadsheet") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Creates an Excel (.xlsx) file with sheets for Notes (including their text), Files (a list " +
                        "of names, folders, keywords and descriptions, not the files themselves), Logins and Folders.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Logins are listed by service name and website only. Usernames, passwords and login notes " +
                        "are never exported.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "The spreadsheet is not encrypted. Anyone who gets the file can read your notes in it. " +
                        "To move your data safely, use an encrypted backup instead.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Choose location") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Shows progress (cannot be dismissed) and then the outcome of a backup, restore or export. */
@Composable
fun TransferDialog(state: TransferState, onDismiss: () -> Unit) {
    when (state) {
        TransferState.Idle -> Unit
        is TransferState.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Please wait") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Keep TAZ open until this finishes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {}
        )
        is TransferState.Finished -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(state.title) },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
        is TransferState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(state.title) },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
    }
}
