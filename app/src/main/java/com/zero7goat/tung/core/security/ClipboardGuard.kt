package com.zero7goat.tung.core.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies secrets to the clipboard and clears them again after a delay.
 *
 * The clip is marked sensitive, so Android 13+ hides it from clipboard previews. Clearing is
 * best effort: it is a timer inside the app process, and Android restricts clipboard access
 * for apps in the background, so a clear can fail or be skipped.
 */
object ClipboardGuard {

    /** How long a copied secret stays on the clipboard. Kept in step with the clipboard setting. */
    @Volatile
    var clearAfterSeconds: Int = 30

    private const val CLIP_LABEL = "TAZ"
    private const val SENSITIVE_KEY_LEGACY = "android.content.extra.IS_SENSITIVE"

    private val handler = Handler(Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    fun copySensitive(
        context: Context,
        text: String,
        delaySeconds: Int = clearAfterSeconds
    ) {
        val appContext = context.applicationContext
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        clip.description.extras = PersistableBundle().apply {
            val key = if (Build.VERSION.SDK_INT >= 33) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                SENSITIVE_KEY_LEGACY
            }
            putBoolean(key, true)
        }
        clipboard.setPrimaryClip(clip)

        pendingClear?.let { handler.removeCallbacks(it) }
        val clear = Runnable {
            clearIfStillOurs(clipboard)
            pendingClear = null
        }
        pendingClear = clear
        handler.postDelayed(clear, delaySeconds * 1000L)
    }

    private fun clearIfStillOurs(clipboard: ClipboardManager) {
        try {
            val label = clipboard.primaryClipDescription?.label?.toString()
            // If the label is readable and different, the user copied something else since.
            if (label != null && label != CLIP_LABEL) return
            if (Build.VERSION.SDK_INT >= 28) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {
            // Clipboard access can be denied in the background; nothing more we can do.
        }
    }
}
