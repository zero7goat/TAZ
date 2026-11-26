package com.zero7goat.taz.core.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zero7goat.taz.core.security.FieldCipherException
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import com.zero7goat.taz.core.security.VaultMode
import com.zero7goat.taz.core.settings.AutoBackupFrequency
import com.zero7goat.taz.core.settings.FeaturePrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * The passphrase used for automatic backups. A backup made while nobody is at the phone needs it,
 * so it is stored, but only as Keystore ciphertext (the same key that protects passwords), never
 * as plain text. The user chose it and needs it to restore.
 */
object AutoBackupSecret {
    private const val AAD = "taz.autobackup.v1|passphrase"

    suspend fun save(context: Context, passphrase: CharArray) {
        val cipher = KeystoreFieldCipher()
        val text = String(passphrase)
        val encoded = Base64.encodeToString(cipher.encrypt(text, AAD), Base64.NO_WRAP)
        FeaturePrefs(context).update { it[FeaturePrefs.AUTO_BACKUP_SECRET] = encoded }
    }

    suspend fun clear(context: Context) {
        FeaturePrefs(context).update { it.remove(FeaturePrefs.AUTO_BACKUP_SECRET) }
    }

    suspend fun isSet(context: Context): Boolean = load(context)?.also { it.fill('\u0000') } != null

    /** The passphrase, or null if none is stored or it can't be decrypted. Wipe it after use. */
    suspend fun load(context: Context): CharArray? {
        val prefs = FeaturePrefs(context)
        val encoded = prefs.rawSecret() ?: return null
        return try {
            KeystoreFieldCipher().decrypt(Base64.decode(encoded, Base64.NO_WRAP), AAD).toCharArray()
        } catch (e: FieldCipherException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }


}

/** Schedules and runs automatic backups with WorkManager (on the device; no network involved). */
object AutoBackupScheduler {
    private const val PERIODIC = "taz-auto-backup"
    private const val NOW = "taz-auto-backup-now"

    fun apply(context: Context, frequency: AutoBackupFrequency) {
        val work = WorkManager.getInstance(context)
        if (frequency == AutoBackupFrequency.OFF) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(frequency.days.toLong(), TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun runNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val prefs = FeaturePrefs(app)
        val settings = prefs.current()
        // The decoy vault (duress passcode) is never backed up over the real backups.
        if (VaultMode.isDecoy || settings.autoBackupFolder.isEmpty()) return Result.success()
        val passphrase = AutoBackupSecret.load(app) ?: run {
            prefs.recordAutoBackup(0L, "The backup passphrase is missing. Set up automatic backups again.")
            return Result.failure()
        }
        return try {
            val tree = Uri.parse(settings.autoBackupFolder)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = PREFIX + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date()) + SUFFIX
            val target = DocumentsContract.createDocument(app.contentResolver, parent, "application/octet-stream", name)
                ?: throw BackupException(BackupError.CANNOT_WRITE)
            BackupService.create(app).createBackup(target, passphrase, allowUnreadableLogins = true) { }
            prune(app, tree, settings.autoBackupKeep)
            prefs.recordAutoBackup(System.currentTimeMillis(), "")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            prefs.recordAutoBackup(0L, "TAZ can no longer write to the backup folder. Choose it again.")
            Result.failure()
        } catch (e: Exception) {
            prefs.recordAutoBackup(0L, "The last automatic backup failed. Check free space in the backup folder.")
            Result.retry()
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /** Deletes the oldest automatic backups beyond [keep]. Only files TAZ named itself are touched. */
    private fun prune(context: Context, tree: Uri, keep: Int) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val ours = mutableListOf<Pair<String, String>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (name.startsWith(PREFIX) && name.endsWith(SUFFIX)) ours += id to name
            }
        }
        // Names carry the date (yyyy-MM-dd HHmm), so sorting by name sorts by age.
        ours.sortedByDescending { it.second }.drop(keep.coerceAtLeast(1)).forEach { (id, _) ->
            try {
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            } catch (e: Exception) {
                // Left for the next run.
            }
        }
    }

    companion object {
        const val PREFIX = "TAZ auto backup "
        const val SUFFIX = ".tazbackup"
    }
}
