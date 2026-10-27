package com.zero7goat.tung.core.export

import java.time.ZoneOffset
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpreadsheetExporterTest {

    private val data = ExportData(
        folders = listOf(
            ExportFolder("a", "NOTES", "Work", null),
            ExportFolder("b", "NOTES", "Projects", "a"),
            ExportFolder("f", "FILES", "Scans", null)
        ),
        notes = listOf(
            ExportNote("Plan ✨ 日本", "desc", "line1\nline2", "b", "Green", true, false, 2, 1_700_000_000_000, 1_700_000_100_000),
            ExportNote("=HYPERLINK(\"http://evil\")", "", "", null, "", false, false, 0, 0, 0)
        ),
        files = listOf(
            ExportFile("scan.pdf", "IMG_1.pdf", "f", "application/pdf", 12345, "tax, 2026", "@SUM(1)", true, true, 1, 2)
        ),
        logins = listOf(ExportLogin("Bank", "https://bank.example", "bank.example", null, false, 1, 2))
    )

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * Writes the workbook and returns each part's XML text by name. It is read through the ZIP
     * central directory, as spreadsheet programs do.
     */
    private fun export(exportData: ExportData = data): Map<String, String> {
        val file = temp.newFile()
        file.outputStream().use { SpreadsheetExporter(zone = ZoneOffset.UTC).write(it, exportData) }
        return ZipFile(file).use { zip ->
            zip.entries().toList().associate { it.name to zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
        }
    }

    /** All parts as text, with XML character entities decoded so values can be compared directly. */
    private fun allText(parts: Map<String, String>) = unescapeXml(parts.values.joinToString("\n"))

    private fun unescapeXml(xml: String): String =
        Regex("&(#x[0-9a-fA-F]+|#[0-9]+|apos|quot|lt|gt|amp);").replace(xml) { match ->
            val entity = match.groupValues[1]
            when {
                entity.startsWith("#x") -> String(Character.toChars(entity.substring(2).toInt(16)))
                entity.startsWith("#") -> String(Character.toChars(entity.substring(1).toInt()))
                else -> mapOf("apos" to "'", "quot" to "\"", "lt" to "<", "gt" to ">", "amp" to "&").getValue(entity)
            }
        }

    @Test
    fun writesTheExpectedSheets() {
        val workbook = export().getValue("xl/workbook.xml")
        listOf("Notes", "Files", "Logins", "Folders").forEach { assertTrue(it, workbook.contains("name=\"$it\"")) }
    }

    @Test
    fun includesMetadataFolderPathsAndUnicode() {
        val text = allText(export())
        assertTrue(text.contains("Plan ✨ 日本"))
        assertTrue(text.contains("Work / Projects"))
        assertTrue(text.contains("tax, 2026"))
        assertTrue(text.contains("bank.example"))
    }

    @Test
    fun formulaLikeTextIsNeutralized() {
        val parts = export()
        val text = allText(parts)
        assertTrue(text.contains("'=HYPERLINK"))
        assertTrue(text.contains("'@SUM(1)"))
        // No cell is ever written as a formula.
        assertFalse(parts.filterKeys { it.startsWith("xl/worksheets/") }.values.any { it.contains("<f>") })
    }

    @Test
    fun safeCellTextHandlesTriggersControlCharactersAndLength() {
        assertEquals("'+1", SpreadsheetExporter.safeCellText("+1"))
        assertEquals("'-2", SpreadsheetExporter.safeCellText("-2"))
        // Hidden behind leading whitespace, line breaks or control characters, or written full-width.
        assertEquals("'  =1+1", SpreadsheetExporter.safeCellText("  =1+1"))
        assertEquals("'\n@cmd", SpreadsheetExporter.safeCellText("\n@cmd"))
        assertEquals("'=x", SpreadsheetExporter.safeCellText("\u0007=x"))
        assertEquals("'＝1+1", SpreadsheetExporter.safeCellText("＝1+1"))
        assertEquals("plain - text", SpreadsheetExporter.safeCellText("plain - text"))
        assertEquals("a b", SpreadsheetExporter.safeCellText("a\u0000 \u0007b"))
        assertEquals("ok", SpreadsheetExporter.safeCellText("o\uD800k")) // unpaired surrogate removed
        assertEquals("😀", SpreadsheetExporter.safeCellText("😀"))
        val long = SpreadsheetExporter.safeCellText("x".repeat(40_000))
        assertEquals(SpreadsheetExporter.MAX_CELL_CHARS, long.length)
        assertTrue(long.endsWith("[truncated]"))
    }

    @Test
    fun loginSheetHasNoSecretColumns() {
        val sheets = export().filterKeys { it.startsWith("xl/") }.values.joinToString()
        listOf("Password", "Username", "Login notes").forEach { assertFalse(it, sheets.contains(it)) }
    }

    @Test
    fun handlesLargeAndEmptyDatasets() {
        val many = data.copy(notes = (1..3000).map { ExportNote("Note $it", "", "body $it", null, "", false, false, 0, 1, 1) })
        assertTrue(allText(export(many)).contains("Note 3000"))
        val empty = export(ExportData(emptyList(), emptyList(), emptyList(), emptyList()))
        assertTrue(empty.containsKey("xl/workbook.xml"))
    }
}
