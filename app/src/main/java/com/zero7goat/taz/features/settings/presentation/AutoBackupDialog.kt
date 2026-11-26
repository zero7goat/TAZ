package com.zero7goat.taz.features.settings.presentation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.backup.AutoBackupScheduler
import com.zero7goat.taz.core.backup.AutoBackupSecret
import com.zero7goat.taz.core.settings.AutoBackupFrequency
import com.zero7goat.taz.core.settings.FeatureSettings
import com.zero7goat.taz.core.settings.FeaturePrefs
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** A readable label for the folder the user picked. */
private fun folderLabel(uri: String): String =
    if (uri.isEmpty()) "No folder chosen" else Uri.decode(uri.substringAfterLast("/")).substringAfter(':').ifEmpty { "Chosen folder" }

/**
 * Turns automatic backups on or off: how often, where (a folder the user picks), how many to keep,
 * and the passphrase that protects them. Backups are the same encrypted format as manual ones.
 */
@Composable
fun AutoBackupDialog(current: FeatureSettings, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var frequency by remember { mutableStateOf(current.autoBackupFrequency) }
    var folder by remember { mutableStateOf(current.autoBackupFolder) }
    var keep by remember { mutableStateOf(current.autoBackupKeep) }
    var hasSecret by remember { mutableStateOf(false) }
    var changingSecret by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val passphrase = remember { TextFieldState() }
    val confirm = remember { TextFieldState() }

    LaunchedEffect(Unit) { hasSecret = AutoBackupSecret.isSet(context) }
    DisposableEffect(Unit) { onDispose { passphrase.clearText(); confirm.clearText() } }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                folder = uri.toString()
            } catch (e: SecurityException) {
                error = "TAZ couldn't keep access to that folder. Pick another one."
            }
        }
    }

    val needsSecret = frequency != AutoBackupFrequency.OFF && (!hasSecret || changingSecret)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automatic backups") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "TAZ saves an encrypted backup on its own, in a folder you choose (for example on an SD card " +
                        "or a folder your computer copies). Nothing is uploaded.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AutoBackupFrequency.entries.forEach { f ->
                        FilterChip(
                            selected = frequency == f,
                            onClick = { frequency = f },
                            label = { Text(when (f) { AutoBackupFrequency.OFF -> "Off"; AutoBackupFrequency.DAILY -> "Daily"; AutoBackupFrequency.WEEKLY -> "Weekly" }) }
                        )
                    }
                }
                if (frequency != AutoBackupFrequency.OFF) {
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(if (folder.isEmpty()) "Choose folder" else "Folder: ${folderLabel(folder)}") }
                    Text("Keep the newest", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 5, 10).forEach { n ->
                            FilterChip(selected = keep == n, onClick = { keep = n }, label = { Text("$n") })
                        }
                    }
                    if (needsSecret) {
                        Text(
                            "Choose a passphrase for these backups (at least $MIN_PASSPHRASE_LENGTH characters). " +
                                "You'll need it to restore. TAZ keeps it encrypted so it can back up on its own.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedSecureTextField(state = passphrase, label = { Text("Backup passphrase") })
                        OutlinedSecureTextField(state = confirm, label = { Text("Repeat passphrase") })
                    } else if (hasSecret) {
                        TextButton(onClick = { changingSecret = true }) { Text("Change backup passphrase") }
                    }
                    if (current.autoBackupLastAt > 0) {
                        Text(
                            "Last backup: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.autoBackupLastAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (current.autoBackupLastError.isNotEmpty()) {
                        Text(current.autoBackupLastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (frequency != AutoBackupFrequency.OFF && folder.isEmpty()) {
                    error = "Choose a folder for the backups."
                    return@TextButton
                }
                if (needsSecret) {
                    val a = passphrase.text.toString()
                    val b = confirm.text.toString()
                    if (a.length < MIN_PASSPHRASE_LENGTH) {
                        error = "The passphrase needs at least $MIN_PASSPHRASE_LENGTH characters."
                        return@TextButton
                    }
                    if (a != b) {
                        error = "The passphrases don't match."
                        return@TextButton
                    }
                }
                scope.launch {
                    if (needsSecret) {
                        val chars = passphrase.text.toString().toCharArray()
                        try {
                            AutoBackupSecret.save(context, chars)
                        } finally {
                            chars.fill('\u0000')
                        }
                    }
                    if (frequency == AutoBackupFrequency.OFF) AutoBackupSecret.clear(context)
                    FeaturePrefs(context).setAutoBackup(frequency, if (frequency == AutoBackupFrequency.OFF) "" else folder, keep)
                    AutoBackupScheduler.apply(context, frequency)
                    if (frequency != AutoBackupFrequency.OFF) {
                        AutoBackupScheduler.runNow(context)
                        onMessage("Automatic backups are on. The first one is being made now.")
                    } else {
                        onMessage("Automatic backups are off")
                    }
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
