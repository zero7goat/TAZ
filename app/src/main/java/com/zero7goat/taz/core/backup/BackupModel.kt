package com.zero7goat.taz.core.backup

import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.features.passwords.domain.PasswordHistoryEntry

/*
 * What a backup contains, independent of Room. Pure Kotlin so the format can be unit tested.
 * Login records hold decrypted values: they only ever exist in memory and inside the encrypted
 * backup stream, and their toString never prints them.
 */

data class FolderRecord(
    val id: String,
    val module: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long
)

data class NoteRecord(
    val id: String,
    val folderId: String?,
    val title: String,
    val description: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean,
    val isPinned: Boolean,
    val colorIndex: Int,
    /** Decrypted custom fields (format version 2). Only ever in memory and in the encrypted stream. */
    val fields: List<CustomField> = emptyList(),
    /** Format version 3 (TAZ 2.0). */
    val isLocked: Boolean = false,
    val remindAt: Long? = null
)

class CredentialRecord(
    val id: String,
    val folderId: String?,
    val serviceName: String,
    val websiteUrl: String,
    val username: String,
    val password: String,
    val notes: String,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val category: String = "login",
    val fields: List<CustomField> = emptyList(),
    /** Format version 3 (TAZ 2.0): earlier passwords, newest first. */
    val history: List<PasswordHistoryEntry> = emptyList(),
    val isLocked: Boolean = false
) {
    override fun toString(): String = "CredentialRecord(id=$id, secrets=hidden)"
}

/** File metadata. The stored path is deliberately not part of a backup: it is rebuilt from [id]. */
data class FileRecord(
    val id: String,
    val folderId: String?,
    val originalName: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val keywords: String,
    val description: String,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** Format version 3 (TAZ 2.0). */
    val isLocked: Boolean = false
)

/**
 * Everything except the file and image contents, which travel as separate archive entries.
 * [settings] holds non-sensitive preferences by key; passcode data and biometric state are never
 * included.
 */
data class BackupContents(
    val folders: List<FolderRecord>,
    val notes: List<NoteRecord>,
    val credentials: List<CredentialRecord>,
    val files: List<FileRecord>,
    val settings: Map<String, String>,
    val tags: List<TagRecord> = emptyList(),
    val tagLinks: List<TagLinkRecord> = emptyList(),
    val categories: List<CategoryRecord> = emptyList(),
    /** Format version 3 (TAZ 2.0): the user's note templates and which logins were picked for which apps. */
    val noteTemplates: List<NoteTemplateRecord> = emptyList(),
    val appLinks: List<AppLinkRecord> = emptyList()
)

/** A tag (format version 2). */
data class TagRecord(val id: String, val name: String, val createdAt: Long)

/** A tag on a note ("NOTE"), login ("LOGIN") or file ("FILE"). */
data class TagLinkRecord(val tagId: String, val itemType: String, val itemId: String)

/** A custom login category: name, icon and its field template (labels and types, no values). */
data class CategoryRecord(val id: String, val name: String, val icon: String, val fieldsJson: String, val createdAt: Long)

/** A note template the user made (format version 3). */
data class NoteTemplateRecord(val id: String, val name: String, val title: String, val body: String, val createdAt: Long)

/** An app a login was picked for in autofill (format version 3). */
data class AppLinkRecord(val packageName: String, val credentialId: String, val createdAt: Long)
