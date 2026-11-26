package com.zero7goat.taz.features.passwords.domain

import java.util.Locale

/**
 * An offline check for passwords that appear again and again in published password leaks, and
 * for the usual ways people vary them ("Password123!", "p@ssw0rd", "dragon2024"). It uses a small
 * list built into the app, so it works with no internet at all; it is not a lookup of every leak
 * ever published. Pure Kotlin, unit tested.
 */
object LeakCheck {

    /** Words that dominate leaked-password lists. Variations of these are flagged too. */
    private val bases = setOf(
        "password", "passwort", "pass", "qwerty", "qwertz", "azerty", "asdf", "asdfgh", "zxcvbn", "letmein", "welcome",
        "admin", "administrator", "root", "login", "master", "dragon", "monkey", "football", "baseball", "soccer",
        "hockey", "basketball", "iloveyou", "princess", "sunshine", "shadow", "superman", "batman", "spiderman",
        "starwars", "pokemon", "naruto", "trustno", "freedom", "whatever", "nothing", "secret", "default", "changeme",
        "access", "hello", "hellokitty", "charlie", "michael", "jordan", "jennifer", "jessica", "ashley", "daniel",
        "thomas", "robert", "andrew", "joshua", "matthew", "hunter", "killer", "computer", "internet", "samsung",
        "google", "apple", "android", "iphone", "facebook", "instagram", "summer", "winter", "autumn", "spring",
        "flower", "orange", "banana", "chocolate", "cookie", "cheese", "pepper", "ginger", "purple", "silver",
        "golden", "diamond", "angel", "lovely", "loveme", "babygirl", "family", "friends", "mother", "father",
        "blessed", "jesus", "christ", "heaven", "money", "lucky", "liverpool", "chelsea", "arsenal", "barcelona",
        "madrid", "juventus", "maggie", "buster", "tigger", "ginger", "cooper", "bailey", "harley", "ranger",
        "mustang", "ferrari", "corvette", "yankees", "cowboys", "eagles", "lakers", "warrior", "phoenix", "matrix",
        "wizard", "merlin", "gandalf", "zelda", "minecraft", "fortnite", "roblox", "test", "tester", "guest",
        "user", "temp", "abc", "abcd", "qazwsx", "zaq", "india", "pakistan", "bangladesh", "london", "paris"
    )

    /** Exact passwords seen very often that the word rule above does not cover. */
    private val exact = setOf(
        "123456", "1234567", "12345678", "123456789", "1234567890", "0123456789", "987654321", "123123",
        "123123123", "111111", "11111111", "000000", "00000000", "654321", "121212", "112233", "123321",
        "666666", "888888", "999999", "1q2w3e", "1q2w3e4r", "1q2w3e4r5t", "1qaz2wsx", "zaq12wsx", "qwe123",
        "qweasd", "qweasdzxc", "asd123", "abc123", "abcd1234", "a1b2c3", "a1b2c3d4", "aa123456", "q1w2e3r4",
        "qwerty123", "qwertyuiop", "asdfghjkl", "zxcvbnm", "1password", "7777777", "123qwe", "147258369",
        "159753", "159357", "741852963", "789456123", "456789", "pass123", "passw0rd", "p@ssw0rd", "p@ssword",
        "iloveu", "ilove you", "trustno1", "letmein1", "welcome1", "admin1", "admin123", "root123"
    )

    private val leet = mapOf('@' to 'a', '4' to 'a', '0' to 'o', '1' to 'i', '!' to 'i', '3' to 'e', '$' to 's', '5' to 's', '7' to 't', '9' to 'g')

    /** True if [password] is a well-known leaked password or an obvious variation of one. */
    fun isCommonlyLeaked(password: String): Boolean {
        if (password.isEmpty()) return false
        val lower = password.lowercase(Locale.ROOT)
        if (lower in exact) return true
        // Strip what people usually add at the end: digits, years and symbols ("Dragon2024!").
        val core = lower.trimEnd { it.isDigit() || !it.isLetterOrDigit() }.trimStart { !it.isLetterOrDigit() }
        if (core.isEmpty()) return lower.length < 12 // only digits and symbols, and short
        if (core in bases) return true
        val unleet = core.map { leet[it] ?: it }.joinToString("").trimEnd { it.isDigit() }
        if (unleet in bases) return true
        // Two common words stuck together ("iloveyou2", "summerlove") are still guessed quickly.
        return bases.any { first -> unleet.startsWith(first) && unleet.removePrefix(first) in bases }
    }
}
