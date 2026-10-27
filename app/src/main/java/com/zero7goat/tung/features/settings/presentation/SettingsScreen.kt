package com.zero7goat.tung.features.settings.presentation

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.tung.core.security.BiometricAuth
import com.zero7goat.tung.core.security.BiometricAvailability
import com.zero7goat.tung.core.settings.AutoLockTimeout
import com.zero7goat.tung.core.settings.ClipboardClearTimeout
import com.zero7goat.tung.core.settings.FontSize
import com.zero7goat.tung.core.settings.ThemeMode
import com.zero7goat.tung.core.ui.AppFonts
import com.zero7goat.tung.core.ui.ScreenHeader
import com.zero7goat.tung.core.ui.TungIcons
import com.zero7goat.tung.core.ui.findFragmentActivity
import java.time.LocalDate

private sealed interface SettingsDialog {
    data object Theme : SettingsDialog
    data object Font : SettingsDialog
    data object TextSize : SettingsDialog
    data object AutoLock : SettingsDialog
    data object Clipboard : SettingsDialog
    data object SetPasscode : SettingsDialog
    data object LockOptions : SettingsDialog
    data object ChangePasscode : SettingsDialog
    data object TurnOffLock : SettingsDialog
    data object BackupPassphrase : SettingsDialog
    data object RestoreConfirm : SettingsDialog
    data object ExportInfo : SettingsDialog
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "AMOLED (true black)"
}

private fun FontSize.label() = when (this) {
    FontSize.SMALL -> "Small"
    FontSize.DEFAULT -> "Default"
    FontSize.LARGE -> "Large"
    FontSize.EXTRA_LARGE -> "Extra large"
}

private fun AutoLockTimeout.label() = when (this) {
    AutoLockTimeout.IMMEDIATELY -> "Immediately"
    AutoLockTimeout.SECONDS_30 -> "After 30 seconds"
    AutoLockTimeout.MINUTE_1 -> "After 1 minute"
    AutoLockTimeout.MINUTES_5 -> "After 5 minutes"
    AutoLockTimeout.MINUTES_15 -> "After 15 minutes"
    AutoLockTimeout.NEVER -> "Never (only when TAZ restarts)"
}

private fun ClipboardClearTimeout.label() = when (this) {
    ClipboardClearTimeout.SECONDS_10 -> "10 seconds"
    ClipboardClearTimeout.SECONDS_30 -> "30 seconds"
    ClipboardClearTimeout.MINUTE_1 -> "1 minute"
    ClipboardClearTimeout.MINUTES_2 -> "2 minutes"
}

/**
 * Appearance, security and backup settings. Choices are saved with the existing
 * DataStore settings and take effect immediately.
 */
@Composable
fun SettingsScreen(onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext))
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lockEnabled by viewModel.lockEnabled.collectAsStateWithLifecycle()
    val activity = remember(context) { context.findFragmentActivity() }
    val biometric = remember(context) { BiometricAuth.availability(context) }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val busy = transfer is TransferState.Working

    // Locations are always picked by the user with the system file picker (Storage Access Framework).
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
        if (uri != null) viewModel.startBackup(uri) else viewModel.clearPassphrases()
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.selectRestoreFile(uri)
            viewModel.clearPassphrases()
            dialog = SettingsDialog.RestoreConfirm
        }
    }
    val createExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        if (uri != null) viewModel.startExport(uri)
    }

    val biometricSubtitle = when {
        !lockEnabled -> "Turn on app lock first."
        biometric == BiometricAvailability.NOT_ENROLLED -> "Add a fingerprint or face in your device settings first."
        biometric != BiometricAvailability.AVAILABLE -> "Not available on this device."
        else -> "Your passcode always works as well."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader("Settings")

        SectionLabel("Appearance")
        SettingRow("Theme", settings.themeMode.label()) { dialog = SettingsDialog.Theme }
        RowDivider()
        SettingRow("Font", AppFonts.labelFor(settings.fontKey)) { dialog = SettingsDialog.Font }
        RowDivider()
        SettingRow("Text size", settings.fontSize.label()) { dialog = SettingsDialog.TextSize }

        SectionLabel("Security")
        SettingRow("App lock", if (lockEnabled) "On" else "Off") {
            dialog = if (lockEnabled) SettingsDialog.LockOptions else SettingsDialog.SetPasscode
        }
        RowDivider()
        SwitchRow(
            title = "Biometric unlock",
            subtitle = biometricSubtitle,
            checked = settings.biometricEnabled && lockEnabled,
            enabled = lockEnabled && biometric == BiometricAvailability.AVAILABLE
        ) { wantOn ->
            if (!wantOn) {
                viewModel.setBiometricEnabled(false)
            } else if (activity != null) {
                // Prove it works before relying on it.
                BiometricAuth.authenticate(
                    activity = activity,
                    title = "Turn on biometric unlock",
                    subtitle = "Confirm it's you",
                    negativeButtonText = "Cancel",
                    onSuccess = { viewModel.setBiometricEnabled(true) },
                    onFailure = { onMessage("Biometric unlock was not turned on") }
                )
            }
        }
        RowDivider()
        SettingRow("Auto-lock", settings.autoLockTimeout.label(), enabled = lockEnabled) {
            dialog = SettingsDialog.AutoLock
        }
        if (lockEnabled) {
            RowDivider()
            SettingRow("Lock now", null) { viewModel.lock.lockNow() }
        }
        RowDivider()
        SwitchRow(
            title = "Hide in app switcher",
            subtitle = "Also blocks screenshots.",
            checked = settings.hideInRecents,
            enabled = true
        ) { viewModel.setHideInRecents(it) }
        RowDivider()
        SettingRow("Clear copied passwords", settings.clipboardClearTimeout.label()) {
            dialog = SettingsDialog.Clipboard
        }

        SectionLabel("Backup")
        SettingRow("Back up to a file", null, enabled = !busy) {
            viewModel.clearPassphrases()
            dialog = SettingsDialog.BackupPassphrase
        }
        RowDivider()
        SettingRow("Restore from a file", null, enabled = !busy) {
            launchOrReport(onMessage) { pickRestore.launch(arrayOf("*/*")) }
        }
        RowDivider()
        SettingRow("Export to spreadsheet (.xlsx)", null, enabled = !busy) { dialog = SettingsDialog.ExportInfo }
        Text(
            text = "Backups are encrypted with a passphrase you choose and saved only where you pick. Nothing is " +
                "uploaded or backed up automatically. Spreadsheet exports are not encrypted and never include passwords.",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "TAZ works fully offline.\nNo account, no cloud, no tracking.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 32.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
    }

    when (dialog) {
        SettingsDialog.Theme -> OptionDialog(
            title = "Theme",
            options = ThemeMode.entries.map { it to it.label() },
            selected = settings.themeMode,
            onSelect = { viewModel.setThemeMode(it); dialog = null },
            onDismiss = { dialog = null }
        )
        SettingsDialog.Font -> OptionDialog(
            title = "Font",
            options = AppFonts.options.map { it.key to it.label },
            selected = settings.fontKey,
            onSelect = { viewModel.setFontKey(it); dialog = null },
            onDismiss = { dialog = null },
            familyFor = { key -> AppFonts.familyFor(key) }
        )
        SettingsDialog.TextSize -> OptionDialog(
            title = "Text size",
            options = FontSize.entries.map { it to it.label() },
            selected = settings.fontSize,
            onSelect = { viewModel.setFontSize(it); dialog = null },
            onDismiss = { dialog = null }
        )
        SettingsDialog.AutoLock -> OptionDialog(
            title = "Auto-lock",
            options = AutoLockTimeout.entries.map { it to it.label() },
            selected = settings.autoLockTimeout,
            onSelect = { viewModel.setAutoLockTimeout(it); dialog = null },
            onDismiss = { dialog = null }
        )
        SettingsDialog.Clipboard -> OptionDialog(
            title = "Clear copied passwords",
            options = ClipboardClearTimeout.entries.map { it to it.label() },
            selected = settings.clipboardClearTimeout,
            onSelect = { viewModel.setClipboardClearTimeout(it); dialog = null },
            onDismiss = { dialog = null }
        )
        SettingsDialog.SetPasscode -> SetPasscodeDialog(
            onSave = { viewModel.lock.setPasscode(it) },
            onSaved = {
                dialog = null
                onMessage("App lock turned on")
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.LockOptions -> LockOptionsDialog(
            onChange = { dialog = SettingsDialog.ChangePasscode },
            onTurnOff = { dialog = SettingsDialog.TurnOffLock },
            onDismiss = { dialog = null }
        )
        SettingsDialog.ChangePasscode -> ChangePasscodeDialog(
            onChange = { current, new -> viewModel.lock.changePasscode(current, new) },
            onChanged = {
                dialog = null
                onMessage("Passcode changed")
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.TurnOffLock -> TurnOffLockDialog(
            onRemove = { viewModel.lock.removePasscode(it) },
            onRemoved = {
                viewModel.onLockRemoved()
                dialog = null
                onMessage("App lock turned off")
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.BackupPassphrase -> BackupPassphraseDialog(
            passphrase = viewModel.passphrase,
            confirm = viewModel.passphraseConfirm,
            onContinue = {
                dialog = null
                launchOrReport(onMessage) { createBackup.launch("TAZ backup ${LocalDate.now()}.$BACKUP_EXTENSION") }
            },
            onDismiss = {
                viewModel.clearPassphrases()
                dialog = null
            }
        )
        SettingsDialog.RestoreConfirm -> RestoreConfirmDialog(
            passphrase = viewModel.passphrase,
            onRestore = {
                dialog = null
                viewModel.startRestore()
            },
            onDismiss = {
                viewModel.cancelRestore()
                dialog = null
            }
        )
        SettingsDialog.ExportInfo -> ExportInfoDialog(
            onContinue = {
                dialog = null
                launchOrReport(onMessage) { createExport.launch("TAZ export ${LocalDate.now()}.xlsx") }
            },
            onDismiss = { dialog = null }
        )
        null -> Unit
    }

    TransferDialog(state = transfer, onDismiss = viewModel::dismissTransfer)
}

private const val BACKUP_MIME = "application/octet-stream"
private const val BACKUP_EXTENSION = "tazbackup"
private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** Opens the system file picker, or reports that the device has none. */
private fun launchOrReport(onMessage: (String) -> Unit, launch: () -> Unit) {
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        onMessage("No file picker is available on this device")
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun SettingRow(title: String, value: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = textColor,
            style = MaterialTheme.typography.bodyLarge
        )
        if (value != null) {
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.size(6.dp))
        }
        if (enabled) {
            Icon(
                imageVector = TungIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // The whole row is one switch for touch and screen readers (the Switch itself is display only).
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
