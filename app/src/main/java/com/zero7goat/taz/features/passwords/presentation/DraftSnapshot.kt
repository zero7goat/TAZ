package com.zero7goat.taz.features.passwords.presentation

import com.zero7goat.taz.core.security.FieldCipher
import org.json.JSONObject

/** The text of a login form that has not been saved yet. [toString] hides everything. */
class DraftFields(
    val serviceName: String,
    val username: String,
    val password: String,
    val websiteUrl: String,
    val notes: String
) {
    override fun toString(): String = "DraftFields(hidden)"
}

/**
 * Turns unsaved login text into an encrypted blob for Android's saved state, and back.
 *
 * Saved state can outlive the app process, so the text is never put there as plain text: it is
 * encrypted with the same Android Keystore cipher that protects saved logins, and bound (as
 * associated data) to the login's id, so a blob can only be restored into the login it came from.
 * Pure JVM code (org.json and the cipher interface) so it can be unit tested.
 */
object DraftSnapshot {

    fun encode(fields: DraftFields, loginId: String, cipher: FieldCipher): ByteArray {
        val json = JSONObject()
            .put("serviceName", fields.serviceName)
            .put("username", fields.username)
            .put("password", fields.password)
            .put("websiteUrl", fields.websiteUrl)
            .put("notes", fields.notes)
            .toString()
        return cipher.encrypt(json, aad(loginId))
    }

    /** The fields, or null if the blob is damaged, belongs to another login or cannot be decrypted. */
    fun decode(blob: ByteArray, loginId: String, cipher: FieldCipher): DraftFields? =
        try {
            val json = JSONObject(cipher.decrypt(blob, aad(loginId)))
            DraftFields(
                serviceName = json.optString("serviceName"),
                username = json.optString("username"),
                password = json.optString("password"),
                websiteUrl = json.optString("websiteUrl"),
                notes = json.optString("notes")
            )
        } catch (e: Exception) {
            null
        }

    private fun aad(loginId: String) = "taz.draft.v1|$loginId"
}
