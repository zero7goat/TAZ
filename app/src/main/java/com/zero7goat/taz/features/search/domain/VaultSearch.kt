package com.zero7goat.taz.features.search.domain

import java.util.Locale

enum class ResultType(val label: String) { NOTE("Notes"), LOGIN("Logins"), FILE("Files"), FOLDER("Folders") }

/** One piece of searchable text of an item, with a label saying where it comes from ("Username"). */
data class SearchField(val label: String, val text: String)

/**
 * Everything search may look at for one item. Built only from data that is allowed in results:
 * passwords and hidden custom field values are never put into a document (see SearchIndex).
 */
data class SearchDoc(
    val type: ResultType,
    val id: String,
    val title: String,
    /** Extra searchable text: body, username, website, notes, field labels and values, keywords. */
    val fields: List<SearchField>,
    val tagIds: Set<String>,
    val tagNames: List<String>,
    val folderId: String?,
    val isFavorite: Boolean,
    val updatedAt: Long,
    /** A short line shown under the title when nothing more specific matched (never secret). */
    val summary: String = "",
    /** Free-form display hint: a login's category key, a file's MIME type, a folder's module. */
    val hint: String = ""
) {
    override fun toString(): String = "SearchDoc(type=$type, id=$id)"
}

enum class DateRange(val label: String, val days: Int?) {
    ANY("Any time", null), WEEK("Last 7 days", 7), MONTH("Last 30 days", 30), YEAR("Last year", 365)
}

data class SearchFilters(
    /** Empty means every type. */
    val types: Set<ResultType> = emptySet(),
    val favoritesOnly: Boolean = false,
    /** Items must have every one of these tags. */
    val tagIds: Set<String> = emptySet(),
    /** Only items inside this folder or its subfolders. */
    val folderId: String? = null,
    val modified: DateRange = DateRange.ANY
) {
    val isActive: Boolean
        get() = types.isNotEmpty() || favoritesOnly || tagIds.isNotEmpty() || folderId != null || modified != DateRange.ANY

    /** Filters other than the type chips, for the "Filters (n)" badge. */
    val extraCount: Int
        get() = listOf(favoritesOnly, tagIds.isNotEmpty(), folderId != null, modified != DateRange.ANY).count { it }
}

/** Why an item matched: the field and a short snippet around the first match. */
data class SearchHit(
    val doc: SearchDoc,
    val matchedLabel: String?,
    val snippet: String?
)

/**
 * Local, in-memory vault search. A query is split into words (case-insensitive, extra whitespace
 * ignored); an item matches when every word occurs somewhere in its title, fields or tags.
 * Pure Kotlin so it can be unit tested.
 */
object VaultSearch {

    private const val CONTEXT_BEFORE = 24
    private const val CONTEXT_AFTER = 64
    private val WHITESPACE = Regex("\\s+")

    fun words(query: String): List<String> =
        query.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map { it.lowercase(Locale.ROOT) }.distinct()

    /**
     * The matching items, best first: title matches before others, then most recently changed.
     * With a blank query only the filters apply (and nothing is returned when no filter is active).
     * [parentOf] maps folder ids to parent ids, for the folder filter.
     */
    fun search(
        docs: List<SearchDoc>,
        query: String,
        filters: SearchFilters,
        now: Long,
        parentOf: Map<String, String?> = emptyMap()
    ): List<SearchHit> {
        val words = words(query)
        if (words.isEmpty() && !filters.isActive) return emptyList()
        val cutoff = filters.modified.days?.let { now - it * DAY_MS }
        val folderScope = filters.folderId?.let { scope -> folderAndDescendants(scope, parentOf) }

        return docs.asSequence()
            .filter { filters.types.isEmpty() || it.type in filters.types }
            .filter { !filters.favoritesOnly || (it.type != ResultType.FOLDER && it.isFavorite) }
            .filter { filters.tagIds.isEmpty() || (it.type != ResultType.FOLDER && it.tagIds.containsAll(filters.tagIds)) }
            .filter { cutoff == null || it.updatedAt >= cutoff }
            .filter { doc ->
                folderScope == null || when (doc.type) {
                    // A folder matches when it is inside the chosen one (not the chosen folder itself).
                    ResultType.FOLDER -> doc.id != filters.folderId && doc.id in folderScope
                    else -> doc.folderId != null && doc.folderId in folderScope
                }
            }
            .mapNotNull { doc -> match(doc, words) }
            .sortedWith(
                compareByDescending<Pair<SearchHit, Boolean>> { it.second }
                    .thenByDescending { it.first.doc.updatedAt }
            )
            .map { it.first }
            .toList()
    }

    /** The hit and whether a word matched the title, or null when some word matches nowhere. */
    private fun match(doc: SearchDoc, words: List<String>): Pair<SearchHit, Boolean>? {
        if (words.isEmpty()) return SearchHit(doc, null, null) to false
        val title = doc.title.lowercase(Locale.ROOT)
        val tagText = doc.tagNames.joinToString(" ").lowercase(Locale.ROOT)
        val fieldTexts = doc.fields.map { it.text.lowercase(Locale.ROOT) }
        for (word in words) {
            val found = title.contains(word) || tagText.contains(word) || fieldTexts.any { it.contains(word) }
            if (!found) return null
        }
        val titleMatch = words.any { title.contains(it) }

        // Explain the match with the first field (in order) that contains a word not in the title.
        var label: String? = null
        var snippet: String? = null
        val notInTitle = words.filterNot { title.contains(it) }.ifEmpty { if (titleMatch) emptyList() else words }
        if (notInTitle.isNotEmpty()) {
            for ((index, field) in doc.fields.withIndex()) {
                val lowered = fieldTexts[index]
                val hit = notInTitle.firstOrNull { lowered.contains(it) } ?: continue
                label = field.label
                snippet = snippet(field.text, hit)
                break
            }
            if (label == null && notInTitle.any { tagText.contains(it) }) {
                label = "Tag"
                snippet = doc.tagNames.firstOrNull { name -> notInTitle.any { name.lowercase(Locale.ROOT).contains(it) } }
            }
        }
        return SearchHit(doc, label, snippet) to titleMatch
    }

    /** A single-line excerpt of [text] around the first occurrence of [word]. */
    fun snippet(text: String, word: String): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        val lowered = flat.lowercase(Locale.ROOT)
        if (lowered.length != flat.length) return flat.take(CONTEXT_BEFORE + CONTEXT_AFTER)
        val index = lowered.indexOf(word)
        if (index < 0) return flat.take(CONTEXT_BEFORE + CONTEXT_AFTER)
        val start = (index - CONTEXT_BEFORE).coerceAtLeast(0)
        val end = (index + word.length + CONTEXT_AFTER).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }

    /** Where the words of [query] occur in [text], merged and sorted, for highlighting. */
    fun highlightRanges(text: String, query: String): List<IntRange> {
        val words = words(query)
        if (words.isEmpty() || text.isEmpty()) return emptyList()
        val lowered = text.lowercase(Locale.ROOT)
        if (lowered.length != text.length) return emptyList()
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

    private fun folderAndDescendants(root: String, parentOf: Map<String, String?>): Set<String> {
        val result = HashSet<String>()
        for (id in parentOf.keys + root) {
            var current: String? = id
            var steps = 0
            while (current != null && steps++ <= parentOf.size) {
                if (current == root) {
                    result.add(id)
                    break
                }
                current = parentOf[current]
            }
        }
        return result
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
