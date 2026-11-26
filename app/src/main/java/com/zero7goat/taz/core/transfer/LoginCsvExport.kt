package com.zero7goat.taz.core.transfer

import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.CustomFields
import com.zero7goat.taz.core.fields.FieldType

/** One login as the CSV export writes it. [toString] hides secrets. */
class CsvLogin(
    val folder: String,
    val favorite: Boolean,
    val name: String,
    val notes: String,
    val url: String,
    val username: String,
    val password: String,
    val fields: List<CustomField>
) {
    override fun toString(): String = "CsvLogin(name=$name, secrets=hidden)"
}

/**
 * Writes logins as a plain CSV in Bitwarden's column layout, which Bitwarden, Proton Pass,
 * KeePassXC, 1Password and most other managers can import. The file is NOT encrypted: it exists
 * so people can move their data, and the app warns about it before writing. Pure Kotlin.
 */
object LoginCsvExport {

    val HEADER = listOf(
        "folder", "favorite", "type", "name", "notes", "fields", "reprompt",
        "login_uri", "login_username", "login_password", "login_totp"
    )

    fun write(logins: List<CsvLogin>): String = buildString {
        appendLine(HEADER.joinToString(","))
        logins.forEach { login ->
            // The first two-factor field goes in its own column; everything else becomes "label: value" lines.
            val totp = login.fields.firstOrNull { it.type == FieldType.TOTP && it.value.isNotBlank() }
            val others = login.fields.filter { it !== totp && it.value.isNotEmpty() }
            val fieldText = others.joinToString("\n") { field ->
                val value = if (field.type == FieldType.TOGGLE) {
                    if (field.value == CustomFields.TRUE) "true" else "false"
                } else {
                    field.value
                }
                "${field.label.ifBlank { field.type.label }}: $value"
            }
            val cells = listOf(
                login.folder,
                if (login.favorite) "1" else "",
                "login",
                login.name,
                login.notes,
                fieldText,
                "0",
                login.url,
                login.username,
                login.password,
                totp?.value?.trim().orEmpty()
            )
            append(cells.joinToString(",") { Csv.escape(it) })
            append("\n")
        }
    }
}
