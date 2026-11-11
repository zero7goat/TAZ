package com.zero7goat.taz.core.tags

import java.util.Locale

/** Tag rules that need no database. Pure Kotlin so they can be unit tested. */
object TagNames {
    const val MAX_LENGTH = 40
    private val WHITESPACE = Regex("\\s+")

    /** What the user sees: trimmed, inner whitespace collapsed to single spaces, length-limited. */
    fun clean(name: String): String = name.trim().replace(WHITESPACE, " ").take(MAX_LENGTH).trim()

    /** The uniqueness key: [clean] and lower case. */
    fun normalize(name: String): String = clean(name).lowercase(Locale.ROOT)
}
