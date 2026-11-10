package com.zero7goat.taz.features.passwords.domain

import java.security.SecureRandom

data class GeneratorOptions(
    val length: Int = 20,
    val uppercase: Boolean = true,
    val lowercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true
) {
    val hasCharacterSet: Boolean
        get() = uppercase || lowercase || digits || symbols

    val isValid: Boolean
        get() = length in MIN_LENGTH..MAX_LENGTH && hasCharacterSet

    companion object {
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 64
    }
}

/** Generates random passwords with [SecureRandom]. */
object PasswordGenerator {

    private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?"

    /**
     * Returns a password that contains at least one character from every selected set.
     * Throws [IllegalArgumentException] if [options] are invalid (no set selected, or a length
     * outside 8 to 64); the UI never allows that state.
     */
    fun generate(options: GeneratorOptions, random: SecureRandom = SecureRandom()): String {
        require(options.isValid) { "Invalid generator options" }

        val sets = buildList {
            if (options.uppercase) add(UPPERCASE)
            if (options.lowercase) add(LOWERCASE)
            if (options.digits) add(DIGITS)
            if (options.symbols) add(SYMBOLS)
        }
        val pool = sets.joinToString("")
        val chars = CharArray(options.length)

        // One guaranteed character per selected set, the rest from the whole pool.
        sets.forEachIndexed { index, set -> chars[index] = set[random.nextInt(set.length)] }
        for (i in sets.size until chars.size) chars[i] = pool[random.nextInt(pool.length)]

        // Fisher-Yates shuffle so the guaranteed characters are not always first.
        for (i in chars.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return String(chars).also { chars.fill('\u0000') }
    }
}
