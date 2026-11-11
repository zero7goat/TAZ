package com.zero7goat.taz.features.passwords.domain

import com.zero7goat.taz.core.fields.CustomField

/** What a list row shows. Never contains the password, notes or custom fields. */
data class CredentialListItem(
    val id: String,
    val folderId: String?,
    val serviceName: String,
    val websiteUrl: String,
    val domain: String,
    val username: String,
    val isFavorite: Boolean,
    val category: String = Categories.LOGIN.key
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
    val updatedAt: Long,
    val category: String = Categories.LOGIN.key,
    val fields: List<CustomField> = emptyList()
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
    val createdAt: Long,
    val category: String = Categories.LOGIN.key,
    val fields: List<CustomField> = emptyList()
) {
    override fun toString(): String = "CredentialInput(id=$id, secrets=hidden)"
}

/** The one rule for saving: a name always, and a password for the Login category. */
fun canSaveCredential(serviceName: String, password: String, category: String): Boolean =
    serviceName.isNotBlank() && (category != Categories.LOGIN.key || password.isNotEmpty())
