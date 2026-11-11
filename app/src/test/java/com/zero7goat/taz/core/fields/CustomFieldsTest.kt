package com.zero7goat.taz.core.fields

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFieldsTest {

    private fun field(type: FieldType, value: String, label: String = "Label", hidden: Boolean = false) =
        CustomField("id-$label", label, type, value, hidden)

    @Test
    fun encodeAndDecodeRoundTripEveryTypeInOrder() {
        val fields = FieldType.entries.mapIndexed { i, type ->
            CustomField("f$i", "Field $i ✨", type, if (type == FieldType.TOGGLE) "true" else "value $i\nline", hidden = i % 2 == 0)
        }
        assertEquals(fields, CustomFields.decode(CustomFields.encode(fields)))
    }

    @Test
    fun secretFieldsAreAlwaysHidden() {
        assertTrue(field(FieldType.SECRET, "x", hidden = false).isHidden)
        assertTrue(field(FieldType.SECRET, "x").copy(hidden = false).isHidden)
        assertFalse(field(FieldType.TEXT, "x").isHidden)
        assertTrue(field(FieldType.NUMBER, "1234", hidden = true).isHidden)
    }

    @Test
    fun toStringNeverShowsValues() {
        val text = field(FieldType.SECRET, "synthetic-secret-marker").toString() +
            listOf(field(FieldType.TEXT, "visible-marker")).toString()
        assertFalse(text.contains("synthetic-secret-marker"))
        assertFalse(text.contains("visible-marker"))
    }

    @Test
    fun hiddenFieldsAreNotSearchable() {
        assertFalse(CustomFields.isSearchable(field(FieldType.SECRET, "x")))
        assertFalse(CustomFields.isSearchable(field(FieldType.NUMBER, "4111", hidden = true)))
        assertTrue(CustomFields.isSearchable(field(FieldType.EMAIL, "a@example.test")))
    }

    @Test
    fun valuesAreValidatedByType() {
        assertTrue(CustomFields.isValidValue(FieldType.NUMBER, "1234"))
        assertTrue(CustomFields.isValidValue(FieldType.NUMBER, "-12.5"))
        assertFalse(CustomFields.isValidValue(FieldType.NUMBER, "12a"))
        assertTrue(CustomFields.isValidValue(FieldType.DATE, "2026-11-11"))
        assertFalse(CustomFields.isValidValue(FieldType.DATE, "11/11/2026"))
        assertTrue(CustomFields.isValidValue(FieldType.TOGGLE, "false"))
        assertFalse(CustomFields.isValidValue(FieldType.TOGGLE, "maybe"))
        FieldType.entries.forEach { assertTrue(CustomFields.isValidValue(it, "")) }
    }

    @Test
    fun typeChangesThatWouldLoseOrCorruptAValueAreRefused() {
        assertTrue(CustomFields.canChangeType(field(FieldType.TEXT, "1234"), FieldType.NUMBER))
        assertFalse(CustomFields.canChangeType(field(FieldType.TEXT, "abc"), FieldType.NUMBER))
        assertFalse(CustomFields.canChangeType(field(FieldType.TEXT, "abc"), FieldType.TOGGLE))
        assertFalse(CustomFields.canChangeType(field(FieldType.MULTILINE, "a\nb"), FieldType.TEXT))
        assertTrue(CustomFields.canChangeType(field(FieldType.MULTILINE, "a\nb"), FieldType.SECRET))
        assertTrue(CustomFields.canChangeType(field(FieldType.TEXT, ""), FieldType.DATE))
        assertTrue(CustomFields.canChangeType(field(FieldType.NUMBER, "42"), FieldType.TEXT))
    }

    @Test
    fun normalizeDropsEmptyFieldsAndCleansLabels() {
        val fields = listOf(
            field(FieldType.TEXT, "", label = "  "),
            field(FieldType.TOGGLE, "false", label = ""),
            field(FieldType.TEXT, "kept", label = "  Account    number "),
            field(FieldType.TEXT, "", label = "Empty but named")
        )
        val clean = CustomFields.normalize(fields)
        assertEquals(listOf("Account number", "Empty but named"), clean.map { it.label })
    }

    @Test
    fun unknownTypeKeysReadAsText() {
        val decoded = CustomFields.decode("""[{"id":"1","label":"x","type":"from-the-future","value":"v"}]""")
        assertEquals(FieldType.TEXT, decoded.single().type)
    }
}
