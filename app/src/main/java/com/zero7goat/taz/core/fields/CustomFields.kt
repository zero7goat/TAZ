package com.zero7goat.taz.core.fields

import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The kinds of value a custom field can hold. [key] is what is stored (inside the encrypted
 * blob and in backups) and must never change. Pure Kotlin so it can be unit tested.
 */
enum class FieldType(val key: String, val label: String) {
    TEXT("text", "Text"),
    MULTILINE("multiline", "Multiline text"),
    NUMBER("number", "Number"),
    DATE("date", "Date"),
    URL("url", "Web address"),
    EMAIL("email", "Email"),
    PHONE("phone", "Phone"),
    SECRET("secret", "Password / secret"),
    TOGGLE("toggle", "Yes / no");

    companion object {
        fun fromKey(key: String?): FieldType = entries.firstOrNull { it.key == key } ?: TEXT
    }
}

/**
 * One user-defined field of a note or login. [hidden] marks a value as sensitive: it is masked
 * until revealed and is never searched or shown in previews. A [FieldType.SECRET] is always hidden.
 * [value] is plain text in memory only; on disk it exists solely inside an encrypted blob.
 */
class CustomField(
    val id: String,
    val label: String,
    val type: FieldType,
    val value: String,
    hidden: Boolean = false
) {
    val isHidden: Boolean = hidden || type == FieldType.SECRET

    fun copy(
        label: String = this.label,
        type: FieldType = this.type,
        value: String = this.value,
        hidden: Boolean = this.isHidden
    ) = CustomField(id, label, type, value, hidden)

    override fun equals(other: Any?): Boolean =
        other is CustomField && other.id == id && other.label == label && other.type == type &&
            other.value == value && other.isHidden == isHidden

    override fun hashCode(): Int = id.hashCode()

    // Values are never printed.
    override fun toString(): String = "CustomField(id=$id, type=${type.key}, value=hidden)"
}

/** Rules and the storage format for custom fields. */
object CustomFields {

    const val MAX_FIELDS = 50
    const val MAX_LABEL_LENGTH = 60
    const val MAX_VALUE_LENGTH = 10_000

    /** True if [value] is acceptable for [type]. Blank values always are. */
    fun isValidValue(type: FieldType, value: String): Boolean {
        if (value.isEmpty()) return true
        return when (type) {
            FieldType.NUMBER -> value.trim().let { it.isNotEmpty() && NUMBER.matches(it) }
            FieldType.TOGGLE -> value == TRUE || value == FALSE
            FieldType.DATE -> parseDate(value) != null
            else -> true
        }
    }

    /**
     * Whether a field can switch to [newType] without losing or corrupting its value: the current
     * value must also be a valid [newType] value, and multiline text never silently loses line breaks.
     */
    fun canChangeType(field: CustomField, newType: FieldType): Boolean {
        if (field.type == newType) return true
        val value = field.value
        if (value.isEmpty()) return true
        if (field.type == FieldType.MULTILINE && value.contains('\n') && newType != FieldType.SECRET) return false
        return isValidValue(newType, value)
    }

    fun parseDate(value: String): LocalDate? =
        try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            null
        }

    /** True if [field] may appear in search results and previews. */
    fun isSearchable(field: CustomField): Boolean = !field.isHidden

    /** A field label cleaned for storage: trimmed, single spaces, length-limited. */
    fun cleanLabel(label: String): String =
        label.trim().replace(WHITESPACE, " ").take(MAX_LABEL_LENGTH)

    /** Drops fields that carry nothing (no label and no value) and cleans labels. */
    fun normalize(fields: List<CustomField>): List<CustomField> =
        fields.map { it.copy(label = cleanLabel(it.label), value = it.value.take(MAX_VALUE_LENGTH)) }
            .filterNot { it.label.isEmpty() && (it.value.isEmpty() || it.value == FALSE) }
            .take(MAX_FIELDS)

    fun encode(fields: List<CustomField>): String = JSONArray().also { array ->
        fields.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("label", it.label)
                    .put("type", it.type.key)
                    .put("value", it.value)
                    .put("hidden", it.isHidden)
            )
        }
    }.toString()

    /** Reads what [encode] wrote. Throws [JSONException] if the text is not a field list. */
    fun decode(text: String): List<CustomField> {
        if (text.isBlank()) return emptyList()
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CustomField(
                id = o.getString("id"),
                label = o.optString("label", ""),
                type = FieldType.fromKey(o.optString("type")),
                value = o.optString("value", ""),
                hidden = o.optBoolean("hidden", false)
            )
        }
    }

    const val TRUE = "true"
    const val FALSE = "false"
    private val NUMBER = Regex("^[-+]?[0-9]+([.,][0-9]+)?$")
    private val WHITESPACE = Regex("\\s+")
}
