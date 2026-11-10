package com.zero7goat.taz.features.passwords.domain

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic passwords only. */
class PasswordHealthTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_800_000_000_000L
    private val fresh = now - 10 * day
    private val strongA = "k7#Vq2!mZp9\$Lx4Rt"
    private val strongB = "Wb3&nT8@yQe5^Hd1Uc"

    private fun login(id: String, name: String, password: String, updatedAt: Long = fresh) =
        HealthInput(id, name, password, updatedAt)

    @Test
    fun emptyAndShortPasswordsAreWeak() {
        assertTrue(PasswordHealth.isWeak(""))
        assertTrue(PasswordHealth.isWeak("aB3\$x"))
        assertTrue(PasswordHealth.isWeak("aB3\$xY7")) // seven characters
    }

    @Test
    fun commonPasswordsAreWeakWhateverTheCase() {
        assertTrue(PasswordHealth.isWeak("Password123"))
        assertTrue(PasswordHealth.isWeak("QWERTYUIOP"))
        assertTrue(PasswordHealth.isWeak("12345678"))
    }

    @Test
    fun repeatedAndSequentialPasswordsAreWeak() {
        assertTrue(PasswordHealth.isWeak("aaaaaaaaaaaaaa"))
        assertTrue(PasswordHealth.isWeak("abcdefghijkl"))
        assertTrue(PasswordHealth.isWeak("987654321"))
    }

    @Test
    fun shortSingleKindPasswordsAreWeakButLongOnesAreNot() {
        assertTrue(PasswordHealth.isWeak("onlylower")) // nine characters, one kind
        assertFalse(PasswordHealth.isWeak("onlylowercaseXYZ".lowercase())) // sixteen characters: long enough
    }

    @Test
    fun strongPasswordsAreNotWeak() {
        assertFalse(PasswordHealth.isWeak(strongA))
        assertFalse(PasswordHealth.isWeak("correct Horse 9 battery"))
        assertFalse(PasswordHealth.isWeak("Tr0ub4dor&3x"))
    }

    @Test
    fun reusedPasswordsAreGroupedAndDifferentOnesAreNot() {
        val report = PasswordHealth.analyze(
            listOf(
                login("1", "Bank", strongA),
                login("2", "Mail", strongA),
                login("3", "Shop", strongA),
                login("4", "Forum", strongB)
            ),
            now
        )
        assertEquals(1, report.reused.size)
        assertEquals(listOf("1", "2", "3"), report.reused[0].logins.map { it.id }.sorted())
        assertEquals(3, report.reusedLoginCount)
    }

    @Test
    fun emptyPasswordsAreWeakButNotCountedAsReused() {
        val report = PasswordHealth.analyze(listOf(login("1", "A", ""), login("2", "B", "")), now)
        assertEquals(2, report.weak.size)
        assertTrue(report.reused.isEmpty())
    }

    @Test
    fun oldPasswordsAreThoseNotUpdatedForAYear() {
        val report = PasswordHealth.analyze(
            listOf(
                login("1", "Old", strongA, updatedAt = now - 366 * day),
                login("2", "Edge", strongB, updatedAt = now - 364 * day)
            ),
            now
        )
        assertEquals(listOf("1"), report.old.map { it.id })
    }

    @Test
    fun cleanVaultHasNoIssues() {
        val report = PasswordHealth.analyze(listOf(login("1", "A", strongA), login("2", "B", strongB)), now)
        assertTrue(report.isAllGood)
        assertEquals(0, report.issueCount)
        assertEquals(2, report.checked)
    }

    @Test
    fun unreadableLoginsAreReportedNotHidden() {
        val report = PasswordHealth.analyze(listOf(login("1", "A", strongA)), now, unreadable = 3)
        assertEquals(3, report.unreadable)
    }

    @Test
    fun listsAreSortedByNameIgnoringCase() {
        val report = PasswordHealth.analyze(
            listOf(login("1", "zebra", "short"), login("2", "Apple", "short"), login("3", "mango", "short")),
            now
        )
        assertEquals(listOf("Apple", "mango", "zebra"), report.weak.map { it.serviceName })
    }

    @Test
    fun reportAndInputNeverExposeThePassword() {
        val secret = "Zq9\$unique-secret-value"
        val input = login("1", "Bank", secret)
        val report = PasswordHealth.analyze(listOf(input, login("2", "Mail", secret)), now)
        assertFalse(input.toString().contains(secret))
        assertFalse(report.toString().contains(secret))
    }
}
