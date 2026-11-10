package com.zero7goat.taz.features.passwords.domain

/** What a list row shows. Never contains the password or notes. */
data class CredentialListItem(
    val id: String,
    val folderId: String?,
    val serviceName: String,
    val websiteUrl: String,
    val domain: String,
    val username: String,
    val isFavorite: Boolean
)

/** A fully decrypted login. [toString] hides secrets so accidental logging cannot leak them. */
data class CredentialDetail(
    val id: String,
    val folderId: String?,
    val serviceName: String,
    val username: String,
    val password: String,
    val websiteUrl: String,
    val notes: String,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    override fun toString(): String = "CredentialDetail(id=$id, secrets=hidden)"
}

/** Values to save. A [createdAt] of 0 means a new entry. [toString] hides secrets. */
data class CredentialInput(
    val id: String,
    val folderId: String?,
    val serviceName: String,
    val username: String,
    val password: String,
    val websiteUrl: String,
    val notes: String,
    val isFavorite: Boolean,
    val createdAt: Long
) {
    override fun toString(): String = "CredentialInput(id=$id, secrets=hidden)"
}
