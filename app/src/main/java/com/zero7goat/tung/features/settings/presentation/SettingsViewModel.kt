package com.zero7goat.tung.features.settings.presentation

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zero7goat.tung.core.AppContainer
import com.zero7goat.tung.core.backup.BackupException
import com.zero7goat.tung.core.backup.BackupPhase
import com.zero7goat.tung.core.backup.BackupService
import com.zero7goat.tung.core.database.TungDatabase
import com.zero7goat.tung.core.export.SpreadsheetExportService
import com.zero7goat.tung.core.security.AppLockManager
import com.zero7goat.tung.core.settings.AppSettings
import com.zero7goat.tung.core.settings.AutoLockTimeout
import com.zero7goat.tung.core.settings.ClipboardClearTimeout
import com.zero7goat.tung.core.settings.FontSize
import com.zero7goat.tung.core.settings.SettingsRepository
import com.zero7goat.tung.core.settings.ThemeMode
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Reads and changes the saved settings, and exposes the app lock for the passcode dialogs. */
class SettingsViewModel(
    private val repository: SettingsRepository,
    val lock: AppLockManager,
    private val backups: BackupService,
    private val exports: SpreadsheetExportService
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** True while the app lock is on (a passcode is set). */
    val lockEnabled: StateFlow<Boolean> = lock.hasPasscode

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setFontKey(key: String) {
        viewModelScope.launch { repository.setFontKey(key) }
    }

    fun setFontSize(size: FontSize) {
        viewModelScope.launch { repository.setFontSize(size) }
    }

    fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        viewModelScope.launch { repository.setAutoLockTimeout(timeout) }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setBiometricEnabled(enabled) }
    }

    fun setClipboardClearTimeout(timeout: ClipboardClearTimeout) {
        viewModelScope.launch { repository.setClipboardClearTimeout(timeout) }
    }

    fun setHideInRecents(hide: Boolean) {
        viewModelScope.launch { repository.setHideInRecents(hide) }
    }

    /** Biometric unlock only makes sense with a passcode, so it is switched off when the lock is. */
    fun onLockRemoved() {
        setBiometricEnabled(false)
    }

    // ---------- backup, restore and export ----------

    private val _transfer = MutableStateFlow<TransferState>(TransferState.Idle)
    val transfer: StateFlow<TransferState> = _transfer.asStateFlow()

    /**
     * The backup passphrase fields. Plain in-memory state of the ViewModel: never saved, logged or
     * written to instance state, and wiped as soon as an operation starts or is cancelled.
     */
    val passphrase = TextFieldState()
    val passphraseConfirm = TextFieldState()

    /** The backup file picked for restoring, held until the user confirms. */
    override fun onCleared() {
        clearPassphrases()
    }

    var pendingRestore: Uri? = null
        private set

    fun selectRestoreFile(uri: Uri?) {
        pendingRestore = uri
    }

    fun clearPassphrases() {
        passphrase.clearText()
        passphraseConfirm.clearText()
    }

    // Set only after the user explicitly agreed to back up without logins that cannot be decrypted.
    private var allowUnreadableLogins = false

    /** How many logins cannot be decrypted (and so cannot be backed up). Resets the consent above. */
    suspend fun prepareBackup(): Int {
        allowUnreadableLogins = false
        clearPassphrases()
        return try {
            backups.unreadableLoginCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0 // the backup itself checks again and stops if logins cannot be read
        }
    }

    fun allowBackupWithoutUnreadableLogins() {
        allowUnreadableLogins = true
    }

    fun startBackup(uri: Uri) {
        val secret = takePassphrase()
        val allowUnreadable = allowUnreadableLogins
        allowUnreadableLogins = false
        run(working = "Creating encrypted backup...") {
            try {
                val summary = backups.createBackup(uri, secret, allowUnreadable) { phase ->
                    if (phase == BackupPhase.ENCRYPTING) _transfer.value = TransferState.Working("Encrypting and writing backup...")
                }
                TransferState.Finished("Backup created", backupMessage(summary))
            } finally {
                secret.fill('\u0000')
            }
        }
    }

    fun startRestore() {
        val uri = pendingRestore ?: return
        pendingRestore = null
        val secret = takePassphrase()
        run(working = "Checking backup...") {
            try {
                val summary = backups.restore(uri, secret) { phase ->
                    if (phase == BackupPhase.APPLYING) _transfer.value = TransferState.Working("Restoring data...")
                }
                TransferState.Finished("Restore complete", restoreMessage(summary))
            } finally {
                secret.fill('\u0000')
            }
        }
    }

    fun cancelRestore() {
        pendingRestore = null
        clearPassphrases()
    }

    fun startExport(uri: Uri) {
        run(working = "Writing spreadsheet...") {
            val summary = exports.export(uri)
            TransferState.Finished(
                "Spreadsheet exported",
                "Exported  notes,  files and  logins (names and websites only). " +
                    "The file is not encrypted."
            )
        }
    }

    fun dismissTransfer() {
        if (_transfer.value !is TransferState.Working) _transfer.value = TransferState.Idle
    }

    private fun takePassphrase(): CharArray {
        val chars = passphrase.text.toString().toCharArray()
        clearPassphrases()
        return chars
    }

    private fun run(working: String, block: suspend () -> TransferState) {
        if (_transfer.value is TransferState.Working) return
        _transfer.value = TransferState.Working(working)
        viewModelScope.launch {
            _transfer.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                TransferState.Failed(failureTitle(e.error), failureMessage(e.error))
            } catch (e: Exception) {
                // Deliberately no details: the failure may involve private data.
                TransferState.Failed("Something went wrong", "Nothing on this device was changed. Check free storage and try again.")
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(
                        AppContainer.settings(appContext),
                        AppContainer.lockManager(appContext),
                        BackupService.create(appContext),
                        SpreadsheetExportService(appContext, TungDatabase.getInstance(appContext))
                    ) as T
            }
        }
    }
}
