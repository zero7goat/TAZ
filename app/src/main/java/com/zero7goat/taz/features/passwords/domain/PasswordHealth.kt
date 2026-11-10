package com.zero7goat.taz.features.passwords.domain

import java.util.Locale
import java.util.concurrent.TimeUnit

/** What the health check needs to know about one login. [toString] hides the password. */
class HealthInput(
    val id: String,
    val serviceName: String,
    val password: String,
    val updatedAt: Long
) {
    override fun toString(): String = "HealthInput(id=$id, password=hidden)"
}

/** A login named in a report. Never carries the password. */
data class HealthEntry(val id: String, val serviceName: String)

/** Logins that share one password. */
data class ReusedGroup(val logins: List<HealthEntry>)

/**
 * The result of a check. Contains only names and ids, never any password, so it is safe to keep on
 * screen. [unreadable] counts logins that could not be decrypted and so were not checked.
 */
data class HealthReport(
    val checked: Int,
    val unreadable: Int,
    val weak: List<HealthEntry>,
    val reused: List<ReusedGroup>,
    val old: List<HealthEntry>
) {
    val reusedLoginCount: Int get() = reused.sumOf { it.logins.size }
    val issueCount: Int get() = weak.size + reusedLoginCount + old.size
    val isAllGood: Boolean get() = weak.isEmpty() && reused.isEmpty() && old.isEmpty()
}

/**
 * Offline password health check. Everything happens in memory on the device: nothing is sent
 * anywhere, and passwords are compared but never stored or returned. Pure Kotlin so it can be
 * unit tested.
 */
object PasswordHealth {

    /** A password not changed for this long is reported as old. */
    const val OLD_AFTER_DAYS = 365
    private const val MIN_LENGTH = 8
    private const val STRONG_ENOUGH_LENGTH = 12

    fun analyze(logins: List<HealthInput>, now: Long, unreadable: Int = 0): HealthReport {
        val cutoff = now - TimeUnit.DAYS.toMillis(OLD_AFTER_DAYS.toLong())
        val byName = compareBy<HealthEntry>({ it.serviceName.lowercase(Locale.ROOT) }, { it.id })

        val weak = logins.filter { isWeak(it.password) }.map { it.toEntry() }.sortedWith(byName)
        val old = logins.filter { it.updatedAt < cutoff }.map { it.toEntry() }.sortedWith(byName)

        // Empty passwords are already reported as weak; they are not "the same password".
        val reused = logins.filter { it.password.isNotEmpty() }
            .groupBy { it.password }
            .values
            .filter { it.size > 1 }
            .map { group -> ReusedGroup(group.map { it.toEntry() }.sortedWith(byName)) }
            .sortedWith(compareBy({ -it.logins.size }, { it.logins.first().serviceName.lowercase(Locale.ROOT) }))

        return HealthReport(
            checked = logins.size,
            unreadable = unreadable,
            weak = weak,
            reused = reused,
            old = old
        )
    }

    /**
     * True for passwords that are easy to guess: empty, shorter than 8 characters, a very common
     * password, one repeated character, a plain sequence such as "12345678", or short and made
     * from a single kind of character.
     */
    fun isWeak(password: String): Boolean {
        if (password.isEmpty()) return true
        if (password.length < MIN_LENGTH) return true
        if (password.lowercase(Locale.ROOT) in COMMON_PASSWORDS) return true
        if (password.all { it == password[0] }) return true
        if (isSequence(password)) return true
        return password.length < STRONG_ENOUGH_LENGTH && characterKinds(password) < 2
    }

    private fun characterKinds(password: String): Int {
        var lower = false
        var upper = false
        var digit = false
        var other = false
        for (c in password) {
            when {
                c.isLowerCase() -> lower = true
                c.isUpperCase() -> upper = true
                c.isDigit() -> digit = true
                else -> other = true
            }
        }
        return listOf(lower, upper, digit, other).count { it }
    }

    /** Every character is one code point above (or below) the one before: "abcdefgh", "87654321". */
    private fun isSequence(password: String): Boolean {
        val lowered = password.lowercase(Locale.ROOT)
        val steps = lowered.zipWithNext { a, b -> b.code - a.code }
        return steps.all { it == 1 } || steps.all { it == -1 }
    }

    private fun HealthInput.toEntry() = HealthEntry(id, serviceName)

    // A short list of the most common passwords. It is deliberately small and fully offline.
    private val COMMON_PASSWORDS = setOf(
        "password", "password1", "password123", "passw0rd", "p@ssw0rd", "123456789", "12345678",
        "1234567890", "qwertyuiop", "qwerty123", "qwertyui", "iloveyou", "admin123", "welcome1",
        "welcome123", "letmein123", "abc12345", "monkey123", "dragon123", "football1", "baseball1",
        "sunshine1", "princess1", "123123123", "11111111", "00000000", "1q2w3e4r", "1qaz2wsx",
        "zaq12wsx", "changeme", "default123", "test1234"
    )
}
