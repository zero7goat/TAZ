package com.zero7goat.taz.features.passwords.domain

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratorsAndHistoryTest {


    @Test
    fun passphrasesHaveTheChosenShape() {
        val generator = PassphraseGenerator((1..300).map { "word" + ('a' + it % 26) + ('a' + it / 26) }, SecureRandom())
        val phrase = generator.passphrase(PassphraseOptions(words = 4, separator = "-", capitalize = true, addNumber = true))
        val parts = phrase.split("-")
        assertEquals(5, parts.size)
        assertTrue(parts.take(4).all { it[0].isUpperCase() })
        assertTrue(parts.last().all(Char::isDigit))
        val plain = generator.passphrase(PassphraseOptions(words = 3, separator = " ", capitalize = false, addNumber = false))
        assertEquals(3, plain.split(" ").size)
        assertTrue(plain.none(Char::isUpperCase))
        // About 8.2 bits per word for 300 words, plus ~6.6 for the number.
        assertEquals(40, generator.entropyBits(PassphraseOptions(words = 4)))
    }

    @Test
    fun emailAliasesKeepTheInbox() {
        val generator = PassphraseGenerator((1..300).map { "word" + ('a' + it % 26) + ('a' + it / 26) }, SecureRandom())
        val alias = generator.username(UsernameStyle.EMAIL_ALIAS, "jane+old@example.test")
        assertTrue(alias.startsWith("jane+"))
        assertTrue(alias.endsWith("@example.test"))
        assertEquals("", generator.username(UsernameStyle.EMAIL_ALIAS, "not an email"))
        assertEquals(10, generator.username(UsernameStyle.RANDOM).length)
    }

    @Test
    fun historyKeepsReplacedPasswordsNewestFirstWithoutRepeats() {
        var history = emptyList<PasswordHistoryEntry>()
        history = PasswordHistory.afterSave(history, "one", "two", 1)
        history = PasswordHistory.afterSave(history, "two", "two", 2) // unchanged: nothing added
        history = PasswordHistory.afterSave(history, "two", "three", 3)
        assertEquals(listOf("two", "one"), history.map { it.password })
        // Going back to an old password removes it from the history.
        history = PasswordHistory.afterSave(history, "three", "one", 4)
        assertEquals(listOf("three", "two"), history.map { it.password })
        repeat(20) { history = PasswordHistory.afterSave(history, "p$it", "p${it + 1}", 10L + it) }
        assertEquals(PasswordHistory.MAX_ENTRIES, history.size)
        assertEquals(history, PasswordHistory.decode(PasswordHistory.encode(history)))
        assertFalse(history.first().toString().contains("p19"))
    }

    @Test
    fun leakCheckFlagsCommonPasswordsAndTheirVariations() {
        listOf("Password123!", "p@ssw0rd", "Dragon2024", "qwerty", "iloveyou2", "Summer2025!", "123456789", "letmein").forEach {
            assertTrue(it, LeakCheck.isCommonlyLeaked(it))
        }
        listOf("river-Candle-orbit-42", "Xq9#vT2!mLp8", "correct horse battery staple?", "").forEach {
            assertFalse(it, LeakCheck.isCommonlyLeaked(it))
        }
    }

    @Test
    fun healthFindsDuplicatesAndLeakedPasswords() {
        val report = PasswordHealth.analyze(
            listOf(
                HealthInput("1", "Mail", "Summer2025!x", 0, "me", "mail.test"),
                HealthInput("2", "Mail (old)", "Hk3!vR9#qL2m", 0, "Me", "mail.test"),
                HealthInput("3", "Shop", "Hk3!vR9#qL2n", 0, "me", "shop.test")
            ),
            now = 1_000L
        )
        assertEquals(listOf("1", "2"), report.duplicates.single().logins.map { it.id }.sorted())
        assertTrue(report.leaked.isEmpty() || report.leaked.all { it.id == "1" })
    }
}
