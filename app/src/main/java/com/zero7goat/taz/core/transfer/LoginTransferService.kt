package com.zero7goat.taz.core.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.zero7goat.taz.core.database.FolderEntity
import com.zero7goat.taz.core.database.FolderModule
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.FieldType
import com.zero7goat.taz.core.security.FieldCipher
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import com.zero7goat.taz.core.tags.TagRepository
import com.zero7goat.taz.core.tags.TagResult
import com.zero7goat.taz.core.tags.TagTarget
import com.zero7goat.taz.core.totp.Base32
import com.zero7goat.taz.core.totp.MigratedAccount
import com.zero7goat.taz.core.totp.Totp
import com.zero7goat.taz.features.passwords.data.CredentialRepository
import com.zero7goat.taz.features.passwords.domain.Categories
import com.zero7goat.taz.features.passwords.domain.CredentialDetail
import com.zero7goat.taz.features.passwords.domain.CredentialInput
import com.zero7goat.taz.features.passwords.domain.extractDomain
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What an import file holds, shown before anything is saved. Lives only in memory. */
class ImportPreview(
    val format: String,
    val logins: List<ImportedLogin>,
    /** Logins already in TAZ (same website, username and password) or repeated in the file. */
    val duplicates: Int,
    /** Rows that are not logins or have nothing to import. */
    val skipped: Int
) {
    val newCount: Int get() = logins.size - duplicates
    override fun toString(): String = "ImportPreview(format=$format, count=${logins.size})"
}

data class ImportSummary(val imported: Int, val duplicates: Int, val folders: Int)

data class CsvExportSummary(val logins: Int, val unreadable: Int)

data class AuthenticatorImportSummary(val attached: Int, val created: Int, val skipped: Int)

class TransferException(val userMessage: String) : Exception("Transfer failed")

/**
 * Imports logins from other password managers and exports them as CSV. Files are only those the
 * user picked with the system file picker; everything runs on the device. Imported logins are
 * encrypted exactly like logins typed into TAZ. Nothing here logs.
 */
class LoginTransferService(
    private val context: Context,
    private val database: TazDatabase,
    cipher: FieldCipher
) {
    private val credentials = CredentialRepository(database.credentialDao(), cipher)
    private val tags = TagRepository(database.tagDao())

    /** Reads and checks [uri] without saving anything. Throws [TransferException] with a readable message. */
    suspend fun preview(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val text = readText(uri)
        when (val result = LoginImportParser.parse(text)) {
            is ImportParseResult.Failure -> throw TransferException(result.message)
            is ImportParseResult.Success -> {
                val seen = existingKeys().toHashSet()
                var duplicates = 0
                val marked = result.logins.map { login ->
                    val key = keyOf(login)
                    if (!seen.add(key)) duplicates++
                    login
                }
                ImportPreview(result.format, marked, duplicates, result.skipped)
            }
        }
    }

    /**
     * Saves the new logins of [preview] in one transaction: either all of them are added or none.
     * Folders are matched by name (and created when missing); tags likewise.
     */
    suspend fun import(preview: ImportPreview): ImportSummary = withContext(Dispatchers.IO) {
        val seen = existingKeys().toHashSet()
        val now = System.currentTimeMillis()
        val toAdd = preview.logins.filter { seen.add(keyOf(it)) }

        // Encrypt everything first, so the transaction only writes.
        val prepared = toAdd.map { login ->
            val id = UUID.randomUUID().toString()
            val detail = CredentialDetail(
                id = id,
                folderId = null,
                serviceName = login.name.trim().take(200).ifEmpty { "Imported login" },
                username = login.username,
                password = login.password,
                websiteUrl = login.url,
                notes = login.notes,
                isFavorite = login.favorite,
                createdAt = now,
                updatedAt = now,
                category = if (login.password.isEmpty()) Categories.OTHER.key else Categories.LOGIN.key,
                fields = fieldsOf(login)
            )
            Triple(login, detail, credentials.encryptForRestore(detail))
        }

        var foldersCreated = 0
        database.withTransaction {
            val folders = database.folderDao().getByModule(FolderModule.PASSWORDS.name)
                .filter { it.parentId == null }
                .associateBy { it.name.trim().lowercase() }
                .toMutableMap()
            prepared.forEach { (login, _, entity) ->
                val folderName = login.folder.trim()
                val folderId = if (folderName.isEmpty()) null else {
                    folders.getOrPut(folderName.lowercase()) {
                        foldersCreated++
                        FolderEntity(UUID.randomUUID().toString(), FolderModule.PASSWORDS.name, folderName, null, now)
                            .also { database.folderDao().upsert(it) }
                    }.id
                }
                database.credentialDao().upsert(entity.copy(folderId = folderId))
                if (login.tags.isNotEmpty()) {
                    val tagIds = login.tags.mapNotNull { (tags.createOrGet(it) as? TagResult.Ok)?.tag?.id }
                    tags.setItemTags(TagTarget.LOGIN, entity.id, tagIds)
                }
            }
        }
        ImportSummary(imported = prepared.size, duplicates = preview.logins.size - prepared.size, folders = foldersCreated)
    }

    /**
     * Adds two-factor accounts from an authenticator app. Each goes onto the login it belongs to
     * (same name or website and username, without a code yet), or becomes a new entry.
     * Accounts whose key TAZ already has are skipped.
     */
    suspend fun importAuthenticator(accounts: List<MigratedAccount>): AuthenticatorImportSummary = withContext(Dispatchers.IO) {
        val details = credentials.decryptAllForBackup().first.toMutableList()
        val knownKeys = details.flatMap { d -> d.fields.filter { it.type == FieldType.TOTP }.mapNotNull { secretOf(it.value) } }.toMutableSet()
        var attached = 0
        var created = 0
        var skipped = 0
        accounts.forEach { account ->
            val key = secretOf(account.otpauth)
            if (key == null || !knownKeys.add(key)) {
                skipped++
                return@forEach
            }
            val field = CustomField(UUID.randomUUID().toString(), "Two-factor code", FieldType.TOTP, account.otpauth)
            val target = details.firstOrNull { d -> belongsTo(d, account) && d.fields.none { it.type == FieldType.TOTP } }
            if (target != null) {
                val saved = credentials.save(target.toInput(fields = target.fields + field))
                details[details.indexOf(target)] = saved
                attached++
            } else {
                val name = account.issuer.ifBlank { account.account.ifBlank { "Two-factor account" } }
                val saved = credentials.save(
                    CredentialInput(
                        id = UUID.randomUUID().toString(), folderId = null, serviceName = name,
                        username = account.account, password = "", websiteUrl = "", notes = "",
                        isFavorite = false, createdAt = 0L, category = Categories.OTHER.key, fields = listOf(field)
                    )
                )
                details += saved
                created++
            }
        }
        AuthenticatorImportSummary(attached, created, skipped)
    }

    private fun secretOf(value: String): String? = Totp.parse(value)?.let { Base32.encode(it.secret) }

    private fun belongsTo(detail: CredentialDetail, account: MigratedAccount): Boolean {
        val issuer = account.issuer.lowercase().filter { it.isLetterOrDigit() }
        if (issuer.isEmpty()) return false
        val name = detail.serviceName.lowercase().filter { it.isLetterOrDigit() }
        val site = extractDomain(detail.websiteUrl).substringBeforeLast('.').substringAfterLast('.').filter { it.isLetterOrDigit() }
        val sameService = name == issuer || site == issuer || name.contains(issuer)
        val sameUser = account.account.isBlank() || detail.username.isBlank() ||
            detail.username.equals(account.account, ignoreCase = true)
        return sameService && sameUser
    }

    private fun CredentialDetail.toInput(fields: List<CustomField>) = CredentialInput(
        id = id, folderId = folderId, serviceName = serviceName, username = username, password = password,
        websiteUrl = websiteUrl, notes = notes, isFavorite = isFavorite, createdAt = createdAt,
        category = category, fields = fields, isLocked = isLocked
    )

    /** Saves a login received from another phone as a new entry. */
    suspend fun saveShared(login: SharedLogin) = withContext(Dispatchers.IO) {
        credentials.save(
            CredentialInput(
                id = UUID.randomUUID().toString(), folderId = null,
                serviceName = login.name.ifBlank { "Shared login" }, username = login.username,
                password = login.password, websiteUrl = login.url, notes = login.notes, isFavorite = false,
                createdAt = 0L,
                category = if (login.password.isEmpty() && login.category == Categories.LOGIN.key) Categories.OTHER.key else login.category,
                fields = login.fields.map { CustomField(UUID.randomUUID().toString(), it.label, it.type, it.value, it.isHidden) }
            )
        )
    }

    /** Writes every login (outside the trash) as an unencrypted CSV to [uri]. */
    suspend fun exportCsv(uri: Uri): CsvExportSummary = withContext(Dispatchers.IO) {
        val (details, unreadable) = credentials.decryptAllForBackup()
        val folderNames = database.folderDao().getByModule(FolderModule.PASSWORDS.name).associate { it.id to it.name }
        val csv = LoginCsvExport.write(
            details.sortedBy { it.serviceName.lowercase() }.map {
                CsvLogin(
                    folder = it.folderId?.let(folderNames::get).orEmpty(),
                    favorite = it.isFavorite,
                    name = it.serviceName,
                    notes = it.notes,
                    url = it.websiteUrl,
                    username = it.username,
                    password = it.password,
                    fields = it.fields
                )
            }
        )
        try {
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw TransferException("The file couldn't be written.")
            output.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
        } catch (e: Throwable) {
            // Remove the partly written document we created; never any other file.
            try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (_: Exception) {
            }
            if (e is CancellationException) throw e
            if (e is TransferException) throw e
            throw TransferException("The file couldn't be written. Check free storage and try again.")
        }
        CsvExportSummary(details.size, unreadable)
    }

    private fun fieldsOf(login: ImportedLogin): List<CustomField> = buildList {
        login.extraFields.forEach {
            add(CustomField(UUID.randomUUID().toString(), it.label, if (it.hidden) FieldType.SECRET else FieldType.TEXT, it.value))
        }
        if (login.totp.isNotBlank()) {
            // A key TAZ can't turn into codes is still kept, as a hidden secret, so nothing is lost.
            val type = if (Totp.isValid(login.totp)) FieldType.TOTP else FieldType.SECRET
            add(CustomField(UUID.randomUUID().toString(), "Two-factor code", type, login.totp.trim()))
        }
    }

    private suspend fun existingKeys(): List<String> =
        credentials.decryptAllForBackup().first.map {
            LoginImportParser.duplicateKey(extractDomain(it.websiteUrl), it.serviceName, it.username, it.password)
        }

    private fun keyOf(login: ImportedLogin) =
        LoginImportParser.duplicateKey(extractDomain(login.url), login.name.trim(), login.username, login.password)

    private fun readText(uri: Uri): String {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: throw TransferException("The file couldn't be opened.")
        val bytes = input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > LoginImportParser.MAX_BYTES) throw TransferException("The file is too large to be a login export.")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        fun create(context: Context): LoginTransferService {
            val app = context.applicationContext
            return LoginTransferService(app, TazDatabase.getInstance(app), KeystoreFieldCipher())
        }
    }
}
