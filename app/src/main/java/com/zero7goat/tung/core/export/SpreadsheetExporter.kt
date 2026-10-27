package com.zero7goat.tung.core.export

import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet

/*
 * Writes the user-initiated .xlsx export. Pure JVM code (fastexcel), so it can be unit tested.
 *
 * The export is a readable index, not a backup, and it is NOT encrypted. It deliberately never
 * contains passwords, usernames or login notes, nor any file contents (the Files sheet only lists
 * files). Text that a spreadsheet program could treat as a formula is prefixed with an apostrophe.
 */

data class ExportFolder(val id: String, val module: String, val name: String, val parentId: String?)

data class ExportNote(
    val title: String,
    val description: String,
    val body: String,
    val folderId: String?,
    val color: String,
    val isFavorite: Boolean,
    val isPinned: Boolean,
    val imageCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class ExportFile(
    val displayName: String,
    val originalName: String,
    val folderId: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val keywords: String,
    val description: String,
    val isFavorite: Boolean,
    val storedCopyPresent: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/** Login metadata only. There is intentionally no field for usernames, passwords or notes. */
data class ExportLogin(
    val serviceName: String,
    val websiteUrl: String,
    val domain: String,
    val folderId: String?,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

data class ExportData(
    val folders: List<ExportFolder>,
    val notes: List<ExportNote>,
    val files: List<ExportFile>,
    val logins: List<ExportLogin>
)

class SpreadsheetExporter(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val checkCancelled: () -> Unit = {}
) {

    /** Writes [data] as an .xlsx workbook to [out]. Does not close [out]. */
    fun write(out: OutputStream, data: ExportData) {
        val paths = FolderPaths(data.folders)
        val workbook = Workbook(out, "TAZ", "1.0")
        sheet(
            workbook, "Notes",
            listOf("Title", "Description", "Folder", "Text", "Color", "Favorite", "Pinned", "Images", "Created", "Modified"),
            listOf(30.0, 30.0, 25.0, 60.0, 10.0, 10.0, 10.0, 9.0, 18.0, 18.0),
            data.notes
        ) { ws, row, note ->
            ws.text(row, 0, note.title)
            ws.text(row, 1, note.description)
            ws.text(row, 2, paths.pathOf(note.folderId))
            ws.text(row, 3, note.body)
            ws.text(row, 4, note.color)
            ws.yesNo(row, 5, note.isFavorite)
            ws.yesNo(row, 6, note.isPinned)
            ws.value(row, 7, note.imageCount)
            ws.date(row, 8, note.createdAt)
            ws.date(row, 9, note.updatedAt)
        }
        sheet(
            workbook, "Files",
            listOf("Name", "Original name", "Folder", "Type", "Size (bytes)", "Keywords", "Description", "Favorite", "Stored copy", "Added", "Modified"),
            listOf(30.0, 30.0, 25.0, 20.0, 13.0, 25.0, 40.0, 10.0, 12.0, 18.0, 18.0),
            data.files
        ) { ws, row, file ->
            ws.text(row, 0, file.displayName)
            ws.text(row, 1, file.originalName)
            ws.text(row, 2, paths.pathOf(file.folderId))
            ws.text(row, 3, file.mimeType)
            ws.value(row, 4, file.sizeBytes)
            ws.text(row, 5, file.keywords)
            ws.text(row, 6, file.description)
            ws.yesNo(row, 7, file.isFavorite)
            ws.text(row, 8, if (file.storedCopyPresent) "Present" else "Missing")
            ws.date(row, 9, file.createdAt)
            ws.date(row, 10, file.updatedAt)
        }
        sheet(
            workbook, "Logins",
            listOf("Service", "Website", "Domain", "Folder", "Favorite", "Created", "Modified"),
            listOf(30.0, 40.0, 25.0, 25.0, 10.0, 18.0, 18.0),
            data.logins
        ) { ws, row, login ->
            ws.text(row, 0, login.serviceName)
            ws.text(row, 1, login.websiteUrl)
            ws.text(row, 2, login.domain)
            ws.text(row, 3, paths.pathOf(login.folderId))
            ws.yesNo(row, 4, login.isFavorite)
            ws.date(row, 5, login.createdAt)
            ws.date(row, 6, login.updatedAt)
        }
        sheet(
            workbook, "Folders",
            listOf("Section", "Folder"),
            listOf(14.0, 50.0),
            data.folders.sortedWith(compareBy({ it.module }, { paths.pathOf(it.id).lowercase() }))
        ) { ws, row, folder ->
            ws.text(row, 0, sectionName(folder.module))
            ws.text(row, 1, paths.pathOf(folder.id))
        }
        workbook.finish()
    }

    private fun <T> sheet(
        workbook: Workbook,
        name: String,
        headers: List<String>,
        widths: List<Double>,
        items: List<T>,
        writeRow: (Worksheet, Int, T) -> Unit
    ) {
        val ws = workbook.newWorksheet(name)
        headers.forEachIndexed { column, header ->
            ws.value(0, column, header)
            ws.width(column, widths[column])
        }
        ws.range(0, 0, 0, headers.size - 1).style().bold().set()
        ws.freezePane(0, 1)
        items.forEachIndexed { index, item ->
            val row = index + 1
            writeRow(ws, row, item)
            // Rows are written to the output in batches so large exports do not pile up in memory.
            if (row % FLUSH_EVERY_ROWS == 0) {
                checkCancelled()
                ws.flush()
            }
        }
        checkCancelled()
        ws.finish()
    }

    private fun Worksheet.text(row: Int, column: Int, value: String) {
        val safe = safeCellText(value)
        if (safe.isNotEmpty()) value(row, column, safe)
    }

    private fun Worksheet.yesNo(row: Int, column: Int, value: Boolean) = value(row, column, if (value) "Yes" else "No")

    private fun Worksheet.date(row: Int, column: Int, epochMillis: Long) {
        if (epochMillis <= 0L) return
        value(row, column, LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone))
        style(row, column).format("yyyy-mm-dd hh:mm").set()
    }

    private fun sectionName(module: String) = when (module) {
        "NOTES" -> "Notes"
        "PASSWORDS" -> "Logins"
        "FILES" -> "Files"
        else -> module
    }

    /** Folder paths like "Work / Projects". A missing or looping parent ends the path. */
    private class FolderPaths(folders: List<ExportFolder>) {
        private val byId = folders.associateBy { it.id }
        private val cache = HashMap<String, String>()

        fun pathOf(folderId: String?): String {
            if (folderId == null) return ""
            return cache.getOrPut(folderId) {
                val names = ArrayList<String>()
                val seen = HashSet<String>()
                var current = byId[folderId]
                while (current != null && seen.add(current.id)) {
                    names += current.name
                    current = current.parentId?.let { byId[it] }
                }
                names.asReversed().joinToString(" / ")
            }
        }
    }

    companion object {
        /** Excel's limit for the text of one cell. */
        const val MAX_CELL_CHARS = 32_767
        private const val FLUSH_EVERY_ROWS = 500
        private const val TRUNCATED_SUFFIX = " [truncated]"
        // Includes the full-width forms, which some spreadsheet programs also accept.
        private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r', '＝', '＋', '－', '＠')

        /**
         * Makes [value] safe for a cell: removes characters XML cannot hold, prefixes text that
         * starts like a formula with an apostrophe (so spreadsheet programs, and CSV copies of the
         * sheet, never evaluate it), and shortens text beyond Excel's cell limit.
         */
        fun safeCellText(value: String): String {
            val clean = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    Character.isHighSurrogate(c) && i + 1 < value.length && Character.isLowSurrogate(value[i + 1]) -> {
                        clean.append(c).append(value[i + 1])
                        i++
                    }
                    Character.isSurrogate(c) -> Unit // unpaired surrogate
                    c == '\t' || c == '\n' || c == '\r' -> clean.append(c)
                    c < ' ' || c == '￾' || c == '￿' -> Unit
                    else -> clean.append(c)
                }
                i++
            }
            var text = clean.toString()
            // Leading spaces or line breaks are trimmed by some programs (and CSV conversions), so
            // the first visible character decides.
            val first = text.firstOrNull { !it.isWhitespace() || it == '\t' || it == '\r' }
            if (first != null && first in FORMULA_TRIGGERS) text = "'$text"
            if (text.length > MAX_CELL_CHARS) {
                var cut = MAX_CELL_CHARS - TRUNCATED_SUFFIX.length
                if (Character.isHighSurrogate(text[cut - 1])) cut--
                text = text.substring(0, cut) + TRUNCATED_SUFFIX
            }
            return text
        }
    }
}
