package com.zero7goat.tung.features.files.domain

import com.zero7goat.tung.core.database.FolderEntity
import com.zero7goat.tung.core.storage.extensionOf
import com.zero7goat.tung.features.files.data.FileEntity
import java.time.Instant
import java.time.ZoneId

private const val MB = 1024L * 1024L
private const val DAY_MS = 24L * 60L * 60L * 1000L

enum class FileSort(val label: String) {
    NAME_ASC("Name (A to Z)"),
    NAME_DESC("Name (Z to A)"),
    ADDED_NEWEST("Date added (newest first)"),
    ADDED_OLDEST("Date added (oldest first)"),
    SIZE_LARGEST("Size (largest first)"),
    SIZE_SMALLEST("Size (smallest first)"),
    TYPE("Type")
}

enum class SizeFilter(val label: String) {
    ANY("Any size"),
    UNDER_1_MB("Under 1 MB"),
    MB_1_TO_10("1 to 10 MB"),
    OVER_10_MB("Over 10 MB");

    fun matches(bytes: Long): Boolean = when (this) {
        ANY -> true
        UNDER_1_MB -> bytes < MB
        MB_1_TO_10 -> bytes in MB..(10 * MB)
        OVER_10_MB -> bytes > 10 * MB
    }
}

/** Filters on the date a file was added to TUNG. */
enum class DateFilter(val label: String) {
    ANY("Any date"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    THIS_YEAR("This year");

    fun matches(addedAt: Long, now: Long, zone: ZoneId): Boolean = when (this) {
        ANY -> true
        LAST_7_DAYS -> addedAt >= now - 7 * DAY_MS
        LAST_30_DAYS -> addedAt >= now - 30 * DAY_MS
        THIS_YEAR -> {
            val startOfYear = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                .withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
            addedAt >= startOfYear
        }
    }
}

/** Everything that decides which files are listed and in which order. */
data class FilesQuery(
    val text: String = "",
    val favoritesOnly: Boolean = false,
    val sort: FileSort = FileSort.NAME_ASC,
    val typeFilter: FileCategory? = null,
    val sizeFilter: SizeFilter = SizeFilter.ANY,
    val dateFilter: DateFilter = DateFilter.ANY,
    val folderId: String? = null
) {
    val hasActiveFilters: Boolean
        get() = typeFilter != null || sizeFilter != SizeFilter.ANY || dateFilter != DateFilter.ANY
}

/**
 * The result of applying a [FilesQuery]. While [isBrowsing] (no search, favorites or filter),
 * [folders] and [files] are the contents of [currentFolder] (or the top level). Otherwise they
 * are results across all folders.
 */
data class FileSelection(
    val files: List<FileEntity>,
    val folders: List<FolderEntity>,
    val currentFolder: FolderEntity?,
    val path: List<FolderEntity>,
    val isBrowsing: Boolean
)

/**
 * Search matches the file name, keywords, description, or the name of the folder the file is in.
 * Folders whose own name matches are listed too.
 */
fun selectFiles(
    allFiles: List<FileEntity>,
    allFolders: List<FolderEntity>,
    query: FilesQuery,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault()
): FileSelection {
    val folderById = allFolders.associateBy { it.id }
    val current = query.folderId?.let { folderById[it] }
    val needle = query.text.trim()
    val browsing = needle.isEmpty() && !query.favoritesOnly && !query.hasActiveFilters

    val folders: List<FolderEntity>
    val candidates: List<FileEntity>
    if (browsing) {
        folders = allFolders.filter { it.parentId == current?.id }
        candidates = allFiles.filter { file ->
            val folderId = file.folderId
            if (current == null) {
                // Top level also catches a file whose folder no longer exists.
                folderId == null || folderId !in folderById
            } else {
                folderId == current.id
            }
        }
    } else {
        folders = if (needle.isNotEmpty() && !query.favoritesOnly && !query.hasActiveFilters) {
            allFolders.filter { it.name.contains(needle, ignoreCase = true) }
        } else {
            emptyList()
        }
        candidates = allFiles
    }

    val selected = candidates
        .filter { file ->
            (!query.favoritesOnly || file.isFavorite) &&
                (query.typeFilter == null || fileCategory(file.mimeType, file.displayName) == query.typeFilter) &&
                query.sizeFilter.matches(file.sizeBytes) &&
                query.dateFilter.matches(file.createdAt, now, zone) &&
                (needle.isEmpty() || matchesSearch(file, file.folderId?.let { folderById[it]?.name }, needle))
        }
        .sortedWith(comparatorFor(query.sort))

    val path = generateSequence(current) { folder -> folder.parentId?.let { folderById[it] } }
        .toList()
        .asReversed()

    return FileSelection(selected, folders, current, path, browsing)
}

private fun matchesSearch(file: FileEntity, folderName: String?, needle: String): Boolean =
    file.displayName.contains(needle, ignoreCase = true) ||
        file.originalName.contains(needle, ignoreCase = true) ||
        file.keywords.contains(needle, ignoreCase = true) ||
        file.description.contains(needle, ignoreCase = true) ||
        (folderName != null && folderName.contains(needle, ignoreCase = true))

private fun comparatorFor(sort: FileSort): Comparator<FileEntity> {
    val byName = compareBy<FileEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }
    return when (sort) {
        FileSort.NAME_ASC -> byName
        FileSort.NAME_DESC -> byName.reversed()
        FileSort.ADDED_NEWEST -> compareByDescending<FileEntity> { it.createdAt }.then(byName)
        FileSort.ADDED_OLDEST -> compareBy<FileEntity> { it.createdAt }.then(byName)
        FileSort.SIZE_LARGEST -> compareByDescending<FileEntity> { it.sizeBytes }.then(byName)
        FileSort.SIZE_SMALLEST -> compareBy<FileEntity> { it.sizeBytes }.then(byName)
        FileSort.TYPE -> compareBy<FileEntity>(
            { fileCategory(it.mimeType, it.displayName).ordinal },
            { extensionOf(it.displayName) }
        ).then(byName)
    }
}
