package com.zero7goat.taz.features.settings.presentation

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.security.BiometricAuth
import com.zero7goat.taz.core.security.BiometricAvailability
import com.zero7goat.taz.core.settings.AutoLockTimeout
import com.zero7goat.taz.core.settings.ClipboardClearTimeout
import com.zero7goat.taz.core.settings.AutoBackupFrequency
import com.zero7goat.taz.core.settings.FeaturePrefs
import com.zero7goat.taz.core.settings.FeatureSettings
import com.zero7goat.taz.core.settings.AppDisguise
import com.zero7goat.taz.core.settings.AppIcon
import com.zero7goat.taz.core.backup.EmergencySheet
import com.zero7goat.taz.core.transfer.ReceiveLoginQrDialog
import com.zero7goat.taz.core.security.VaultMode
import com.zero7goat.taz.core.reminders.Reminders
import android.os.Build
import com.zero7goat.taz.core.settings.FontSize
import com.zero7goat.taz.core.settings.ThemeMode
import com.zero7goat.taz.core.ui.AppFonts
import com.zero7goat.taz.core.ui.ScreenHeader
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.core.ui.findFragmentActivity
import com.zero7goat.taz.features.autofill.openAutofillSettings
import com.zero7goat.taz.features.autofill.rememberAutofillEnabled
import java.time.LocalDate
import kotlinx.coroutines.launch

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
    data class UnreadableLogins(val count: Int) : SettingsDialog
    data object BackupPassphrase : SettingsDialog
    data object RestoreConfirm : SettingsDialog
    data object ExportInfo : SettingsDialog
    data object ImportInfo : SettingsDialog
    data object ExportCsv : SettingsDialog
    data object AuthenticatorImport : SettingsDialog
    data object AutoBackup : SettingsDialog
    data object Duress : SettingsDialog
    data object WipeAfter : SettingsDialog
    data object AppIconChoice : SettingsDialog
    data object RemindersChoice : SettingsDialog
    data object ReceiveLogin : SettingsDialog
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
fun SettingsScreen(
    onMessage: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPasswordHealth: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenCodes: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext))
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lockEnabled by viewModel.lockEnabled.collectAsStateWithLifecycle()
    val activity = remember(context) { context.findFragmentActivity() }
    val biometric = remember(context) { BiometricAuth.availability(context) }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val scope = rememberCoroutineScope()
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
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.previewImport(uri)
    }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV_MIME)) { uri ->
        if (uri != null) viewModel.startCsvExport(uri)
    }
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val createSheet = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            try {
                EmergencySheet.write(context, uri, FeaturePrefs(context).current().autoBackupFolder)
                onMessage("Emergency sheet saved. Print it and keep it somewhere safe.")
            } catch (e: Exception) {
                onMessage("The sheet couldn't be saved")
            }
        }
    }
    val autofillEnabled = rememberAutofillEnabled()
    val featurePrefs = remember(context) { FeaturePrefs(context) }
    val features by featurePrefs.settings.collectAsStateWithLifecycle(FeatureSettings())
    val hasDuress by viewModel.lock.hasDuress.collectAsStateWithLifecycle()

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
        if (Build.VERSION.SDK_INT >= 31) {
            RowDivider()
            SwitchRow(
                title = "Match wallpaper colours",
                subtitle = "Material You colours from your wallpaper.",
                checked = features.dynamicColor,
                enabled = true
            ) { on -> scope.launch { featurePrefs.setDynamicColor(on) } }
        }

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
        // In the decoy vault, nothing hints that a duress passcode exists.
        if (!VaultMode.isDecoy) {
            SettingRow("Duress passcode", if (hasDuress) "On" else "Off", enabled = lockEnabled) { dialog = SettingsDialog.Duress }
            RowDivider()
        }
        SettingRow(
            "Erase after wrong passcodes",
            if (features.wipeAfterAttempts == 0) "Never" else "After ${features.wipeAfterAttempts}",
            enabled = lockEnabled
        ) { dialog = SettingsDialog.WipeAfter }
        RowDivider()
        SwitchRow(
            title = "Lock when the screen turns off",
            subtitle = null,
            checked = features.lockOnScreenOff && lockEnabled,
            enabled = lockEnabled
        ) { on -> scope.launch { featurePrefs.setLockOnScreenOff(on) } }
        RowDivider()
        SettingRow("App icon", if (features.disguise == AppDisguise.NONE) "TAZ" else features.disguise.label) { dialog = SettingsDialog.AppIconChoice }
        RowDivider()
        SettingRow("Clear copied passwords", settings.clipboardClearTimeout.label()) {
            dialog = SettingsDialog.Clipboard
        }

        SectionLabel("Autofill")
        SettingRow(
            "Fill logins in other apps",
            if (autofillEnabled) "On" else "Off",
            icon = TazIcons.Autofill
        ) {
            if (!openAutofillSettings(context)) onMessage("This device doesn't offer autofill settings")
        }
        Text(
            text = if (autofillEnabled) {
                "TAZ suggests your logins in apps and browsers and offers to save new ones. " +
                    "It asks for your passcode first whenever TAZ is locked. In Chrome, also choose " +
                    "Settings → Autofill services → \"Autofill using another service\"."
            } else {
                "Choose TAZ as your autofill service to fill usernames and passwords in apps and browsers, " +
                    "without copying and pasting."
            },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )

        SectionLabel("Tools")
        SettingRow("Tags", null, icon = TazIcons.Tag) { onOpenTags() }
        RowDivider()
        SettingRow("Login categories", null, icon = TazIcons.Category) { onOpenCategories() }
        RowDivider()
        SettingRow("Trash", null, icon = TazIcons.Trash) { onOpenTrash() }
        RowDivider()
        SettingRow("Password health", null, icon = TazIcons.Shield) { onOpenPasswordHealth() }
        RowDivider()
        SettingRow("Two-factor codes", null, icon = TazIcons.Timer) { onOpenCodes() }
        RowDivider()
        SettingRow("Reminders", null, icon = TazIcons.Calendar) { dialog = SettingsDialog.RemindersChoice }

        SectionLabel("Backup")
        SettingRow("Back up to a file", null, enabled = !busy, icon = TazIcons.Backup) {
            scope.launch {
                val unreadable = viewModel.prepareBackup()
                dialog = if (unreadable > 0) SettingsDialog.UnreadableLogins(unreadable) else SettingsDialog.BackupPassphrase
            }
        }
        RowDivider()
        SettingRow("Restore from a file", null, enabled = !busy, icon = TazIcons.Restore) {
            launchOrReport(onMessage) { pickRestore.launch(arrayOf("*/*")) }
        }
        RowDivider()
        SettingRow(
            "Automatic backups",
            when (features.autoBackupFrequency) {
                AutoBackupFrequency.OFF -> "Off"
                AutoBackupFrequency.DAILY -> "Daily"
                AutoBackupFrequency.WEEKLY -> "Weekly"
            },
            icon = TazIcons.Calendar
        ) { dialog = SettingsDialog.AutoBackup }
        RowDivider()
        SettingRow("Export to spreadsheet (.xlsx)", null, enabled = !busy, icon = TazIcons.File) { dialog = SettingsDialog.ExportInfo }
        RowDivider()
        SettingRow("Emergency sheet (PDF to print)", null, icon = TazIcons.Page) {
            launchOrReport(onMessage) { createSheet.launch("TAZ emergency sheet.pdf") }
        }

        SectionLabel("Move logins")
        SettingRow("Import from another app", null, enabled = !busy, icon = TazIcons.Import) { dialog = SettingsDialog.ImportInfo }
        RowDivider()
        SettingRow("Import from Google Authenticator", null, enabled = !busy, icon = TazIcons.Timer) { dialog = SettingsDialog.AuthenticatorImport }
        RowDivider()
        SettingRow("Export logins to CSV", null, enabled = !busy, icon = TazIcons.Export) { dialog = SettingsDialog.ExportCsv }
        RowDivider()
        SettingRow("Receive a login from another phone", null, enabled = !busy, icon = TazIcons.QrScan) { dialog = SettingsDialog.ReceiveLogin }
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
        is SettingsDialog.UnreadableLogins -> UnreadableLoginsDialog(
            count = (dialog as SettingsDialog.UnreadableLogins).count,
            onContinue = {
                viewModel.allowBackupWithoutUnreadableLogins()
                dialog = SettingsDialog.BackupPassphrase
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
        SettingsDialog.ImportInfo -> ImportInfoDialog(
            onContinue = {
                dialog = null
                launchOrReport(onMessage) { pickImport.launch(arrayOf("*/*")) }
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.Duress -> DuressPasscodeDialog(
            hasDuress = hasDuress,
            onSet = { current, duress -> viewModel.lock.setDuress(current, duress) },
            onRemove = { viewModel.lock.removeDuress() },
            onDone = { message -> dialog = null; onMessage(message) },
            onDismiss = { dialog = null }
        )
        SettingsDialog.WipeAfter -> WipeAfterDialog(
            current = features.wipeAfterAttempts,
            onSelect = { count -> scope.launch { featurePrefs.setWipeAfterAttempts(count) } },
            onDismiss = { dialog = null }
        )
        SettingsDialog.AppIconChoice -> AppIconDialog(
            current = features.disguise,
            onSelect = { choice ->
                scope.launch {
                    featurePrefs.setDisguise(choice)
                    AppIcon.apply(context, choice)
                    onMessage(if (choice == AppDisguise.NONE) "The TAZ icon is back" else "TAZ now appears as ${choice.label}")
                }
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.RemindersChoice -> RemindersDialog(
            expiryDays = features.expiryReminderDays,
            passwordMonths = features.passwordAgeMonths,
            onExpiry = { days -> scope.launch { featurePrefs.setExpiryReminderDays(days); Reminders.scheduleCheck(context) } },
            onPasswords = { months -> scope.launch { featurePrefs.setPasswordAgeMonths(months); Reminders.scheduleCheck(context) } },
            onDismiss = { dialog = null }
        )
        SettingsDialog.ReceiveLogin -> ReceiveLoginQrDialog(
            onSave = { login -> dialog = null; viewModel.receiveShared(login) },
            onDismiss = { dialog = null }
        )
        SettingsDialog.AutoBackup -> AutoBackupDialog(current = features, onMessage = onMessage, onDismiss = { dialog = null })
        SettingsDialog.AuthenticatorImport -> AuthenticatorImportDialog(
            onImport = { accounts ->
                dialog = null
                viewModel.importAuthenticator(accounts)
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.ExportCsv -> ExportCsvDialog(
            lockEnabled = lockEnabled,
            onVerify = { viewModel.lock.verify(it) },
            onContinue = {
                dialog = null
                launchOrReport(onMessage) { createCsv.launch("TAZ logins ${LocalDate.now()}.csv") }
            },
            onDismiss = { dialog = null }
        )
        null -> Unit
    }

    importPreview?.let { preview ->
        ImportPreviewDialog(preview = preview, onImport = viewModel::confirmImport, onDismiss = viewModel::cancelImport)
    }

    TransferDialog(state = transfer, onDismiss = viewModel::dismissTransfer)
}

private const val BACKUP_MIME = "application/octet-stream"
private const val BACKUP_EXTENSION = "tazbackup"
private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val CSV_MIME = "text/csv"

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
private fun SettingRow(
    title: String,
    value: String?,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
            )
            Spacer(Modifier.size(16.dp))
        }
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
                imageVector = TazIcons.ChevronRight,
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
