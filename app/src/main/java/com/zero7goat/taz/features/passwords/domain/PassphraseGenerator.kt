package com.zero7goat.taz.features.passwords.domain

import java.security.SecureRandom
import kotlin.math.ln
import kotlin.math.roundToInt

data class PassphraseOptions(
    val words: Int = 5,
    val separator: String = "-",
    val capitalize: Boolean = true,
    val addNumber: Boolean = true
) {
    companion object {
        const val MIN_WORDS = 3
        const val MAX_WORDS = 10
        val SEPARATORS = listOf("-", ".", "_", " ", "")
    }
}

enum class UsernameStyle { WORDS, RANDOM, EMAIL_ALIAS }

/**
 * Passphrases ("River-Candle-Orbit-42") and usernames from a word list shipped with the app,
 * using [SecureRandom]. Pure Kotlin; the word list is passed in so tests can use a fixed one.
 */
class PassphraseGenerator(words: List<String>, private val random: SecureRandom = SecureRandom()) {

    private val words: List<String> = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    init {
        require(this.words.size >= 100) { "Word list too short" }
    }

    fun passphrase(options: PassphraseOptions): String {
        val count = options.words.coerceIn(PassphraseOptions.MIN_WORDS, PassphraseOptions.MAX_WORDS)
        val parts = MutableList(count) { pick().let { w -> if (options.capitalize) w.replaceFirstChar(Char::uppercase) else w } }
        if (options.addNumber) parts += random.nextInt(100).toString().padStart(2, '0')
        return parts.joinToString(options.separator)
    }

    /** Roughly how many bits of randomness a passphrase with [options] has. */
    fun entropyBits(options: PassphraseOptions): Int {
        val perWord = ln(words.size.toDouble()) / ln(2.0)
        val number = if (options.addNumber) ln(100.0) / ln(2.0) else 0.0
        return (perWord * options.words + number).roundToInt()
    }

    /**
     * A username: two words and a number ("sunnyotter42"), random letters and digits, or an alias of
     * [email] ("name+word42@example.com", which most email providers deliver to the same inbox).
     */
    fun username(style: UsernameStyle, email: String = ""): String = when (style) {
        UsernameStyle.WORDS -> pick() + pick().replaceFirstChar(Char::uppercase) + random.nextInt(100)
        UsernameStyle.RANDOM -> buildString {
            val chars = "abcdefghijkmnpqrstuvwxyz23456789"
            repeat(10) { append(chars[random.nextInt(chars.length)]) }
        }
        UsernameStyle.EMAIL_ALIAS -> {
            val at = email.indexOf('@')
            if (at <= 0 || at == email.lastIndex) "" else {
                val local = email.substring(0, at).substringBefore('+')
                "$local+${pick()}${random.nextInt(100)}${email.substring(at)}"
            }
        }
    }

    private fun pick(): String = words[random.nextInt(words.size)]
}
