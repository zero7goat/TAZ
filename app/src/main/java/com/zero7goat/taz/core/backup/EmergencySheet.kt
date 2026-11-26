package com.zero7goat.taz.core.backup

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A one-page PDF to print and keep somewhere safe: how to get TAZ back after losing or replacing
 * the phone, with lines to write the backup passphrase by hand. It contains no secrets at all;
 * nothing from the vault is written into it.
 */
object EmergencySheet {

    suspend fun write(context: Context, uri: Uri, autoBackupFolder: String) = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val canvas = page.canvas
            val margin = 56f
            val width = 595 - 2 * margin.toInt()
            var y = margin

            fun block(text: String, size: Float, bold: Boolean = false, gap: Float = 10f, color: Int = Color.BLACK) {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size
                    this.color = color
                    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
                canvas.save()
                canvas.translate(margin, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + gap
            }

            fun line(label: String) {
                block(label, 11f, gap = 4f, color = Color.DKGRAY)
                val paint = Paint().apply { color = Color.GRAY; strokeWidth = 1f }
                y += 18f
                canvas.drawLine(margin, y, 595 - margin, y, paint)
                y += 18f
            }

            block("TAZ emergency sheet", 22f, bold = true, gap = 4f)
            block("Printed " + DateFormat.getDateInstance(DateFormat.LONG).format(Date()), 10f, color = Color.DKGRAY, gap = 18f)
            block(
                "TAZ keeps everything on your phone only: there is no account and no cloud copy. If the phone is lost, " +
                    "broken or reset, your data can only come back from a TAZ backup file (.tazbackup) and its passphrase.",
                12f, gap = 16f
            )
            block("To get your data back", 14f, bold = true, gap = 6f)
            block(
                "1. Install TAZ on the new phone.\n" +
                    "2. Copy your newest .tazbackup file onto it (from your computer, SD card or USB stick).\n" +
                    "3. Open TAZ → Settings → Restore from a file, pick the backup and enter its passphrase.\n" +
                    "4. Turn on the app lock again, and autofill if you use it.",
                12f, gap = 16f
            )
            block("Where my backups are", 14f, bold = true, gap = 6f)
            block(
                if (autoBackupFolder.isNotEmpty()) "Automatic backups are saved to a folder on this phone (" +
                    Uri.decode(autoBackupFolder.substringAfterLast("/")).substringAfter(':') + "). Copy them somewhere else too."
                else "Write down where you keep your backup files:",
                12f, gap = 4f
            )
            line("")
            line("Backup passphrase (write it by hand, never type it into a computer):")
            line("Passphrase hint (optional):")
            block(
                "Keep this sheet somewhere safe and private, like with important papers. Anyone with the passphrase and a " +
                    "backup file can read your data. The passcode that unlocks TAZ is not needed to restore.",
                10f, color = Color.DKGRAY, gap = 0f
            )
            document.finishPage(page)
            val out = context.contentResolver.openOutputStream(uri, "w") ?: throw BackupException(BackupError.CANNOT_WRITE)
            out.use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}
