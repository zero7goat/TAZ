package com.zero7goat.taz.features.passwords.domain

import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.FieldType
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Icons a category can use. [key] is stored and must never change. */
enum class CategoryIcon(val key: String) {
    LOGIN("login"), WIFI("wifi"), BANK("bank"), CARD("card"), IDENTITY("identity"),
    LICENSE("license"), NOTE("note"), OTHER("other"), KEY("key"), SHIELD("shield"), FOLDER("folder"), STAR("star");

    companion object {
        fun fromKey(key: String?): CategoryIcon = entries.firstOrNull { it.key == key } ?: OTHER

        /** The icons offered for custom categories. */
        val choices = listOf(OTHER, KEY, SHIELD, FOLDER, STAR, LOGIN, WIFI, BANK, CARD, IDENTITY, LICENSE, NOTE)
    }
}

/** A field a category suggests for a new entry. Only labels and types: never a value. */
data class FieldTemplate(val label: String, val type: FieldType, val hidden: Boolean = false)

/**
 * What kind of entry a login is. Built-in categories have fixed keys; a custom category's key is
 * [CUSTOM_PREFIX] plus its id. [usesLoginFields] decides whether the username, password and
 * website fields are offered (they are still shown for any entry where they hold a value).
 */
data class LoginCategory(
    val key: String,
    val name: String,
    val icon: CategoryIcon,
    val usesLoginFields: Boolean,
    val template: List<FieldTemplate>,
    val isBuiltIn: Boolean
) {
    /** New, empty fields for a fresh entry of this category. */
    fun newFields(): List<CustomField> =
        template.map { CustomField(UUID.randomUUID().toString(), it.label, it.type, "", it.hidden) }
}

object Categories {

    const val CUSTOM_PREFIX = "custom:"

    // DO NOT CHANGE the keys: they are stored with every entry and in backups.
    val LOGIN = LoginCategory("login", "Login", CategoryIcon.LOGIN, true, emptyList(), true)
    val WIFI = LoginCategory(
        "wifi", "Wi-Fi", CategoryIcon.WIFI, false,
        listOf(
            FieldTemplate("Network name", FieldType.TEXT),
            FieldTemplate("Password", FieldType.SECRET),
            FieldTemplate("Security", FieldType.TEXT)
        ),
        true
    )
    val BANK = LoginCategory(
        "bank", "Bank account", CategoryIcon.BANK, false,
        listOf(
            FieldTemplate("Account holder", FieldType.TEXT),
            FieldTemplate("Account number", FieldType.NUMBER, hidden = true),
            FieldTemplate("Routing / IFSC / sort code", FieldType.TEXT),
            FieldTemplate("IBAN", FieldType.TEXT, hidden = true),
            FieldTemplate("Branch", FieldType.TEXT)
        ),
        true
    )
    val CARD = LoginCategory(
        "card", "Payment card", CategoryIcon.CARD, false,
        listOf(
            FieldTemplate("Cardholder name", FieldType.TEXT),
            FieldTemplate("Card number", FieldType.NUMBER, hidden = true),
            FieldTemplate("Expiry (MM/YY)", FieldType.TEXT),
            FieldTemplate("Security code", FieldType.NUMBER, hidden = true),
            FieldTemplate("PIN", FieldType.NUMBER, hidden = true)
        ),
        true
    )
    val IDENTITY = LoginCategory(
        "identity", "Identity document", CategoryIcon.IDENTITY, false,
        listOf(
            FieldTemplate("Full name", FieldType.TEXT),
            FieldTemplate("Document number", FieldType.TEXT, hidden = true),
            FieldTemplate("Issued by", FieldType.TEXT),
            FieldTemplate("Issue date", FieldType.DATE),
            FieldTemplate("Expiry date", FieldType.DATE)
        ),
        true
    )
    val LICENSE = LoginCategory(
        "license", "Software license", CategoryIcon.LICENSE, false,
        listOf(
            FieldTemplate("Licensed to", FieldType.TEXT),
            FieldTemplate("Email", FieldType.EMAIL),
            FieldTemplate("License key", FieldType.SECRET),
            FieldTemplate("Version", FieldType.TEXT),
            FieldTemplate("Purchase date", FieldType.DATE)
        ),
        true
    )
    val SECURE_NOTE = LoginCategory("secure_note", "Secure note", CategoryIcon.NOTE, false, emptyList(), true)
    val OTHER = LoginCategory("other", "Other", CategoryIcon.OTHER, false, emptyList(), true)

    val builtIn: List<LoginCategory> = listOf(LOGIN, WIFI, BANK, CARD, IDENTITY, LICENSE, SECURE_NOTE, OTHER)

    fun isCustomKey(key: String) = key.startsWith(CUSTOM_PREFIX)

    fun customKey(id: String) = CUSTOM_PREFIX + id

    /** The category for [key]. An unknown key (for example a deleted custom category) reads as Other. */
    fun resolve(key: String, custom: List<LoginCategory>): LoginCategory =
        builtIn.firstOrNull { it.key == key } ?: custom.firstOrNull { it.key == key } ?: OTHER

    fun encodeTemplate(template: List<FieldTemplate>): String = JSONArray().also { array ->
        template.forEach {
            array.put(JSONObject().put("label", it.label).put("type", it.type.key).put("hidden", it.hidden))
        }
    }.toString()

    fun decodeTemplate(text: String): List<FieldTemplate> =
        try {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                FieldTemplate(o.optString("label", ""), FieldType.fromKey(o.optString("type")), o.optBoolean("hidden", false))
            }.filter { it.label.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
}
