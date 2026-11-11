package com.zero7goat.taz.features.passwords.domain

import com.zero7goat.taz.core.fields.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {

    @Test
    fun builtInKeysNeverChange() {
        // Stored with every entry and in backups (see DataIdentityTest for the other stored names).
        assertEquals(
            listOf("login", "wifi", "bank", "card", "identity", "license", "secure_note", "other"),
            Categories.builtIn.map { it.key }
        )
    }

    @Test
    fun unknownOrDeletedCategoriesReadAsOther() {
        assertEquals(Categories.OTHER, Categories.resolve("custom:deleted", emptyList()))
        assertEquals(Categories.OTHER, Categories.resolve("", emptyList()))
        assertEquals(Categories.WIFI, Categories.resolve("wifi", emptyList()))
    }

    @Test
    fun onlyLoginsNeedAPassword() {
        assertFalse(canSaveCredential("Mail", "", "login"))
        assertTrue(canSaveCredential("Mail", "pw", "login"))
        assertTrue(canSaveCredential("Home Wi-Fi", "", "wifi"))
        assertFalse(canSaveCredential("   ", "pw", "wifi"))
    }

    @Test
    fun sensitiveTemplateFieldsStartHidden() {
        val card = Categories.CARD.newFields().associateBy { it.label }
        assertTrue(card.getValue("Card number").isHidden)
        assertTrue(card.getValue("Security code").isHidden)
        assertFalse(card.getValue("Cardholder name").isHidden)
        assertTrue(Categories.WIFI.newFields().first { it.type == FieldType.SECRET }.isHidden)
        assertTrue(Categories.CARD.newFields().all { it.value.isEmpty() })
    }

    @Test
    fun templatesRoundTripAndBadTemplatesReadAsEmpty() {
        val template = listOf(FieldTemplate("Member no.", FieldType.NUMBER, hidden = true), FieldTemplate("Since", FieldType.DATE))
        assertEquals(template, Categories.decodeTemplate(Categories.encodeTemplate(template)))
        assertTrue(Categories.decodeTemplate("not json").isEmpty())
    }
}
