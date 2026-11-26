package com.zero7goat.taz.core.transfer

import org.json.JSONException
import org.json.JSONObject

/*
 * Reads logins exported by other password managers. Pure Kotlin/JVM (unit tested); the files are
 * read from storage the user picked and parsed on the device. Nothing is uploaded.
 *
 * Supported: CSV from Chrome / Edge / Brave, Firefox, Bitwarden, KeePass / KeePassXC, 1Password,
 * Proton Pass, Dashlane, Samsung Pass-style and other CSVs with recognisable column names, and
 * Bitwarden's unencrypted JSON export.
 */

/** One login read from an export. [toString] hides secrets. */
class ImportedLogin(
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val notes: String = "",
    val totp: String = "",
    val folder: String = "",
    val favorite: Boolean = false,
    val tags: List<String> = emptyList(),
    val extraFields: List<ImportedField> = emptyList()
) {
    override fun toString(): String = "ImportedLogin(name=$name, secrets=hidden)"
}

class ImportedField(val label: String, val value: String, val hidden: Boolean) {
    override fun toString(): String = "ImportedField(label=$label)"
}

sealed interface ImportParseResult {
    /** [skipped] counts rows that are not logins (cards, notes) or carry nothing usable. */
    data class Success(val format: String, val logins: List<ImportedLogin>, val skipped: Int) : ImportParseResult
    data class Failure(val message: String) : ImportParseResult
}

object LoginImportParser {

    const val MAX_BYTES = 20L * 1024 * 1024

    fun parse(text: String): ImportParseResult {
        // Excel and some managers start the file with a byte-order mark (U+FEFF).
        val clean = if (text.isNotEmpty() && text[0].code == 0xFEFF) text.substring(1) else text
        if (clean.isBlank()) return ImportParseResult.Failure("The file is empty.")
        return if (clean.trimStart().startsWith("{")) parseBitwardenJson(clean) else parseCsv(clean)
    }

    // ---------- CSV ----------

    private enum class Column { NAME, URL, USERNAME, EMAIL, PASSWORD, NOTES, TOTP, FOLDER, FAVORITE, TAGS, TYPE }

    private val aliases: Map<String, Column> = buildMap {
        listOf("name", "title", "account", "accountname", "service", "itemname", "displayname", "site", "sitename")
            .forEach { put(it, Column.NAME) }
        listOf("url", "loginuri", "uri", "website", "weburl", "loginurl", "address", "urls", "webaddress", "link")
            .forEach { put(it, Column.URL) }
        listOf("username", "loginusername", "user", "login", "userid", "loginname", "username1")
            .forEach { put(it, Column.USERNAME) }
        listOf("email", "emailaddress", "loginemail", "mail").forEach { put(it, Column.EMAIL) }
        listOf("password", "loginpassword", "pass", "pwd", "passwd").forEach { put(it, Column.PASSWORD) }
        listOf("notes", "note", "comments", "comment", "extra", "notesplain", "memo", "description")
            .forEach { put(it, Column.NOTES) }
        listOf("totp", "logintotp", "otpauth", "otp", "otpsecret", "onetimepassword", "2fa", "twofactor", "authenticator", "otpurl")
            .forEach { put(it, Column.TOTP) }
        listOf("folder", "group", "grouping", "folders", "path").forEach { put(it, Column.FOLDER) }
        listOf("favorite", "favourite", "fav", "starred").forEach { put(it, Column.FAVORITE) }
        listOf("tags", "labels", "tag").forEach { put(it, Column.TAGS) }
        listOf("type", "itemtype", "kind").forEach { put(it, Column.TYPE) }
    }

    private fun headerKey(header: String) = header.lowercase().filter { it.isLetterOrDigit() }

    private fun parseCsv(text: String): ImportParseResult {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } } ?: ','
        val rows = Csv.parse(text, delimiter)
        if (rows.size < 2) return ImportParseResult.Failure("No logins were found in this file.")
        val header = rows.first()
        val columns = HashMap<Column, Int>()
        header.forEachIndexed { index, title ->
            val column = aliases[headerKey(title)] ?: return@forEachIndexed
            columns.putIfAbsent(column, index)
        }
        if (Column.PASSWORD !in columns) {
            return ImportParseResult.Failure(
                "This file has no password column. Export a CSV with your logins from the other app and try again."
            )
        }
        val format = detectCsvFormat(header.map(::headerKey))
        var skipped = 0
        val logins = rows.drop(1).mapNotNull { row ->
            if (row.all { it.isBlank() }) return@mapNotNull null
            fun cell(column: Column) = columns[column]?.let { row.getOrNull(it) }?.trim().orEmpty()
            val type = cell(Column.TYPE).lowercase()
            if (type.isNotEmpty() && type != "login" && type != "1" && type != "password") {
                skipped++
                return@mapNotNull null
            }
            val password = columns[Column.PASSWORD]?.let { row.getOrNull(it) }.orEmpty() // never trimmed
            var username = cell(Column.USERNAME)
            val email = cell(Column.EMAIL)
            val extra = mutableListOf<ImportedField>()
            if (username.isEmpty()) username = email else if (email.isNotEmpty() && email != username) {
                extra += ImportedField("Email", email, hidden = false)
            }
            val url = cell(Column.URL).lineSequence().firstOrNull().orEmpty().trim()
            if (password.isEmpty() && username.isEmpty()) {
                skipped++
                return@mapNotNull null
            }
            ImportedLogin(
                name = cell(Column.NAME).ifEmpty { fallbackName(url, username) },
                url = url,
                username = username,
                password = password,
                notes = cell(Column.NOTES),
                totp = cell(Column.TOTP),
                folder = cleanFolder(cell(Column.FOLDER)),
                favorite = cell(Column.FAVORITE).lowercase() in setOf("1", "true", "yes", "y", "x", "*"),
                tags = splitTags(cell(Column.TAGS)),
                extraFields = extra
            )
        }
        if (logins.isEmpty()) return ImportParseResult.Failure("No logins were found in this file.")
        return ImportParseResult.Success(format, logins, skipped)
    }

    private fun detectCsvFormat(keys: List<String>): String = when {
        "loginuri" in keys && "loginpassword" in keys -> "Bitwarden CSV"
        "httprealm" in keys || "formactionorigin" in keys -> "Firefox CSV"
        "group" in keys && "title" in keys -> "KeePass CSV"
        "otpauth" in keys && "title" in keys -> "1Password CSV"
        "vault" in keys && "email" in keys && "totp" in keys -> "Proton Pass CSV"
        "otpsecret" in keys || "username2" in keys -> "Dashlane CSV"
        keys == listOf("name", "url", "username", "password", "note") ||
            keys == listOf("name", "url", "username", "password") -> "Chrome CSV"
        else -> "CSV"
    }

    // ---------- Bitwarden JSON ----------

    private fun parseBitwardenJson(text: String): ImportParseResult {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return ImportParseResult.Failure("This file isn't a supported export.")
        }
        if (root.optBoolean("encrypted", false)) {
            return ImportParseResult.Failure(
                "This export is encrypted. In Bitwarden, export again as \"JSON\" (not \"JSON (Encrypted)\")."
            )
        }
        val items = root.optJSONArray("items")
            ?: return ImportParseResult.Failure("This file isn't a supported export.")
        val folders = HashMap<String, String>()
        root.optJSONArray("folders")?.let { array ->
            for (i in 0 until array.length()) {
                val folder = array.optJSONObject(i) ?: continue
                folders[folder.optString("id")] = folder.optString("name")
            }
        }
        var skipped = 0
        val logins = mutableListOf<ImportedLogin>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val login = item.optJSONObject("login")
            if (item.optInt("type", 1) != 1 || login == null) {
                skipped++
                continue
            }
            val url = login.optJSONArray("uris")?.optJSONObject(0)?.optString("uri").orEmpty().trim()
            val username = login.optString("username").takeUnless { login.isNull("username") }.orEmpty().trim()
            val password = login.optString("password").takeUnless { login.isNull("password") }.orEmpty()
            if (username.isEmpty() && password.isEmpty()) {
                skipped++
                continue
            }
            val extra = mutableListOf<ImportedField>()
            item.optJSONArray("fields")?.let { fields ->
                for (f in 0 until fields.length()) {
                    val field = fields.optJSONObject(f) ?: continue
                    val type = field.optInt("type", 0)
                    if (type == 3) continue // "linked" fields point at other fields; nothing to keep
                    val value = field.optString("value").takeUnless { field.isNull("value") }.orEmpty()
                    val label = field.optString("name").takeUnless { field.isNull("name") }.orEmpty()
                    if (label.isEmpty() && value.isEmpty()) continue
                    extra += ImportedField(label, value, hidden = type == 1)
                }
            }
            val folderId = item.optString("folderId").takeUnless { item.isNull("folderId") }.orEmpty()
            logins += ImportedLogin(
                name = item.optString("name").takeUnless { item.isNull("name") }.orEmpty().trim()
                    .ifEmpty { fallbackName(url, username) },
                url = url,
                username = username,
                password = password,
                notes = item.optString("notes").takeUnless { item.isNull("notes") }.orEmpty().trim(),
                totp = login.optString("totp").takeUnless { login.isNull("totp") }.orEmpty().trim(),
                folder = folders[folderId].orEmpty(),
                favorite = item.optBoolean("favorite", false),
                extraFields = extra
            )
        }
        if (logins.isEmpty()) return ImportParseResult.Failure("No logins were found in this file.")
        return ImportParseResult.Success("Bitwarden JSON", logins, skipped)
    }

    // ---------- helpers ----------

    /** A readable name when the export has none: the website's host, else the username. */
    fun fallbackName(url: String, username: String): String {
        val host = url.substringAfter("://", url).substringBefore('/').substringBefore('?')
            .substringAfterLast('@').substringBefore(':').removePrefix("www.")
        return host.ifEmpty { username }.ifEmpty { "Imported login" }
    }

    /** KeePass groups look like "Root/Internet/Shopping": keep the innermost name, drop "Root". */
    private fun cleanFolder(raw: String): String {
        val parts = raw.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }
        val meaningful = parts.filterNot { it.equals("root", ignoreCase = true) }
        return meaningful.lastOrNull().orEmpty().take(60)
    }

    private fun splitTags(raw: String): List<String> =
        raw.split(',', ';', '|').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20)

    /** What counts as "the same login" when skipping duplicates. */
    fun duplicateKey(host: String, name: String, username: String, password: String): String =
        listOf(host.ifEmpty { name }.lowercase(), username.lowercase(), password).joinToString("\u0000")
}

/** A small RFC 4180 CSV reader: quoted fields, doubled quotes, line breaks inside quotes. */
object Csv {
    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(c)
                }
            } else when (c) {
                '"' -> if (cell.isEmpty()) inQuotes = true else cell.append(c)
                delimiter -> { row.add(cell.toString()); cell.clear() }
                '\r' -> Unit
                '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }

    /** Quotes a value when it needs it. Values are written exactly (no formula escaping: passwords must survive). */
    fun escape(value: String, delimiter: Char = ','): String =
        if (value.any { it == '"' || it == delimiter || it == '\n' || it == '\r' } || value != value.trim()) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
