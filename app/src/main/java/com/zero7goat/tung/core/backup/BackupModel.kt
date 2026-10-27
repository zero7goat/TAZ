package com.zero7goat.tung.core.backup

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
    val colorIndex: Int
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
    val updatedAt: Long
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
    val updatedAt: Long
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
    val settings: Map<String, String>
)
