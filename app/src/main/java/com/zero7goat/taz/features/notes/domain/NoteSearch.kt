package com.zero7goat.taz.features.notes.domain

import java.util.Locale

/**
 * Searching notes. A note matches when every word of the query appears somewhere in its title,
 * description or text (in any order, ignoring upper/lower case). Pure Kotlin so it can be unit
 * tested.
 */
object NoteSearch {

    private const val CONTEXT_BEFORE = 28
    private const val CONTEXT_AFTER = 72
    private val WHITESPACE = Regex("\\s+")
    private val IMAGE_REFERENCE = Regex("!\\[[^\\]]*]\\([^)]*\\)")

    /** The distinct words of [query], lower-cased. Empty when the query is blank. */
    fun words(query: String): List<String> =
        query.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map { it.lowercase(Locale.ROOT) }.distinct()

    fun matches(title: String, description: String, body: String, query: String): Boolean {
        val words = words(query)
        if (words.isEmpty()) return true
        val haystack = "$title\n$description\n$body".lowercase(Locale.ROOT)
        return words.all { haystack.contains(it) }
    }

    /**
     * A short piece of the note's text around the first word of [query] found in [description] or
     * [body], with line breaks turned into spaces, for showing why the note matched. Null when the
     * query is blank or none of its words occur in the text (for example it matched only the title).
     */
    fun snippet(description: String, body: String, query: String): String? {
        val words = words(query)
        if (words.isEmpty()) return null
        val text = listOf(description, body)
            .map { IMAGE_REFERENCE.replace(it, "[image]").replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        if (text.isEmpty()) return null

        val lowered = text.lowercase(Locale.ROOT)
        if (lowered.length != text.length) return text.take(CONTEXT_BEFORE + CONTEXT_AFTER) // case mapping shifted positions
        var best = -1
        var bestLength = 0
        for (word in words) {
            val index = lowered.indexOf(word)
            if (index >= 0 && (best < 0 || index < best)) {
                best = index
                bestLength = word.length
            }
        }
        if (best < 0) return null

        val start = (best - CONTEXT_BEFORE).coerceAtLeast(0)
        val end = (best + bestLength + CONTEXT_AFTER).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).trim() + suffix
    }

    /** Where the words of [query] occur in [text], for highlighting. Ranges do not overlap. */
    fun highlightRanges(text: String, query: String): List<IntRange> {
        val words = words(query)
        if (words.isEmpty() || text.isEmpty()) return emptyList()
        val lowered = text.lowercase(Locale.ROOT)
        if (lowered.length != text.length) return emptyList() // case mapping changed the length
        val ranges = ArrayList<IntRange>()
        for (word in words) {
            var from = 0
            while (from <= lowered.length - word.length) {
                val index = lowered.indexOf(word, from)
                if (index < 0) break
                ranges.add(index until index + word.length)
                from = index + word.length
            }
        }
        ranges.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged.add(range)
            }
        }
        return merged
    }
}
