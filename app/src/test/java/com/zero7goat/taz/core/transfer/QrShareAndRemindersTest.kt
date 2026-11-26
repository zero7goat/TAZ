package com.zero7goat.taz.core.transfer

import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.FieldType
import com.zero7goat.taz.core.reminders.ReminderRules
import com.zero7goat.taz.features.passwords.domain.CredentialDetail
import com.zero7goat.taz.features.passwords.domain.PasswordHistoryEntry
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** All data is synthetic. */
class QrShareAndRemindersTest {

    private val login = SharedLogin(
        "Example", "https://example.test", "alice", "s3cret!", "a note", "login",
        listOf(CustomField("1", "PIN", FieldType.SECRET, "1234"))
    )

    @Test
    fun aSharedLoginOpensOnlyWithItsCode() {
        val code = QrShare.newCode()
        assertEquals(QrShare.CODE_LENGTH, code.length)
        val text = QrShare.encode(login, code)
        assertTrue(QrShare.isShareCode(text))
        assertFalse(text.contains("s3cret"))
        val opened = QrShare.decode(text, QrShare.formatCode(code).lowercase())
        assertNotNull(opened)
        assertEquals("alice", opened!!.username)
        assertEquals("s3cret!", opened.password)
        assertEquals("1234", opened.fields.single().value)
        assertNull(QrShare.decode(text, "AAAAAAAA"))
        assertNull(QrShare.decode("hello", code))
        // A changed byte is detected.
        val tampered = text.dropLast(4) + (if (text.endsWith("AAAA")) "BBBB" else "AAAA")
        assertNull(QrShare.decode(tampered, code))
    }

    private fun detail(fields: List<CustomField>, createdAt: Long = 0, history: List<PasswordHistoryEntry> = emptyList(), locked: Boolean = false) =
        CredentialDetail("id", null, "Card", "", "pw", "", "", false, createdAt, createdAt, fields = fields, history = history, isLocked = locked)

    private fun day(date: String) = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun expiryRemindersFindDatesAndMonthYearFields() {
        val now = day("2026-11-26")
        val items = ReminderRules.expiring(
            listOf(
                detail(listOf(CustomField("a", "Expiry date", FieldType.DATE, "2026-12-10"))),
                detail(listOf(CustomField("b", "Expiry (MM/YY)", FieldType.TEXT, "11/26"))),
                detail(listOf(CustomField("c", "Issue date", FieldType.DATE, "2026-12-01"))), // not an expiry
                detail(listOf(CustomField("d", "Expiry date", FieldType.DATE, "2027-06-01"))) // too far away
            ),
            now, days = 30, zone = ZoneOffset.UTC
        )
        assertEquals(listOf("2026-11-30", "2026-12-10"), items.map { it.date.toString() })
        assertEquals("on " + items[1].date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)) + " (in 14 days)", items[1].whenText)
        assertTrue(ReminderRules.expiring(listOf(detail(listOf(CustomField("a", "Expiry", FieldType.DATE, "2026-11-27")))), now, 0).isEmpty())
    }

    @Test
    fun oldPasswordRemindersUseTheLastChange() {
        val now = day("2026-11-26")
        val yearAgo = now - 400L * 24 * 60 * 60 * 1000
        val old = detail(emptyList(), createdAt = yearAgo)
        val recentlyChanged = detail(emptyList(), createdAt = yearAgo, history = listOf(PasswordHistoryEntry("older", now - 1000)))
        assertEquals(listOf("Card"), ReminderRules.oldPasswords(listOf(old, recentlyChanged), now, 12))
        assertEquals(listOf("a locked login"), ReminderRules.oldPasswords(listOf(detail(emptyList(), yearAgo, locked = true)), now, 12))
        assertTrue(ReminderRules.oldPasswords(listOf(old), now, 0).isEmpty())
    }
}
