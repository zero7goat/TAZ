package com.zero7goat.taz.features.passwords.domain

import org.json.JSONArray
import org.json.JSONObject

/** An earlier password of a login and when it was replaced. [toString] hides the password. */
class PasswordHistoryEntry(val password: String, val replacedAt: Long) {
    override fun equals(other: Any?) = other is PasswordHistoryEntry && other.password == password && other.replacedAt == replacedAt
    override fun hashCode() = replacedAt.hashCode()
    override fun toString(): String = "PasswordHistoryEntry(replacedAt=$replacedAt, password=hidden)"
}

/** Rules and storage format for password history. Stored only inside an encrypted blob. Pure Kotlin. */
object PasswordHistory {

    const val MAX_ENTRIES = 10

    /**
     * The history after a save: when the password changed, the old one goes first (newest first),
     * without repeating the new password, and only the newest [MAX_ENTRIES] are kept.
     */
    fun afterSave(history: List<PasswordHistoryEntry>, oldPassword: String?, newPassword: String, now: Long): List<PasswordHistoryEntry> {
        val updated = if (!oldPassword.isNullOrEmpty() && oldPassword != newPassword) {
            listOf(PasswordHistoryEntry(oldPassword, now)) + history
        } else {
            history
        }
        return updated.filterNot { it.password == newPassword }
            .distinctBy { it.password }
            .take(MAX_ENTRIES)
    }

    fun encode(entries: List<PasswordHistoryEntry>): String = JSONArray().also { array ->
        entries.forEach { array.put(JSONObject().put("password", it.password).put("replacedAt", it.replacedAt)) }
    }.toString()

    fun decode(text: String): List<PasswordHistoryEntry> {
        if (text.isBlank()) return emptyList()
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            PasswordHistoryEntry(o.optString("password", ""), o.optLong("replacedAt", 0L))
        }.filter { it.password.isNotEmpty() }
    }
}
