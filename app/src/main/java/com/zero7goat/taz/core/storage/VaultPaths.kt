package com.zero7goat.taz.core.storage

import android.content.Context
import com.zero7goat.taz.core.security.VaultMode
import java.io.File

/**
 * Where the vault's files live: filesDir for the real vault (unchanged since 1.0), or a separate
 * folder for the decoy vault that the duress passcode opens. Settings and the passcode are shared.
 */
object VaultPaths {
    const val DECOY_DIR = "decoy"

    fun root(context: Context): File {
        val base = context.applicationContext.filesDir
        return if (VaultMode.isDecoy) File(base, DECOY_DIR).also { it.mkdirs() } else base
    }
}
