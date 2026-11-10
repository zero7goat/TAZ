package com.zero7goat.taz.core.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.zero7goat.taz.core.database.TazDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The recovery path for a forgotten or unreadable passcode. TAZ has no accounts and no cloud,
 * so a forgotten passcode cannot be recovered. This erases everything TAZ stores on the device
 * (database, stored files, settings, passcode and the encryption key) so the app can be used
 * again from scratch. It is deliberately explicit and irreversible.
 */
object AppReset {

    suspend fun eraseEverything(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        TazDatabase.closeAndForget()
        app.deleteDatabase(TazDatabase.DATABASE_NAME)
        File(app.filesDir, "vault").deleteRecursively()
        File(app.cacheDir, "share").deleteRecursively()
        deleteMasterKey()
        File(app.filesDir, "datastore").deleteRecursively()
    }

    /** Restarts the app in a fresh process, so no old state or cached key survives. */
    fun restart(activity: Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)?.component
        if (launch != null) activity.startActivity(Intent.makeRestartActivityTask(launch))
        Runtime.getRuntime().exit(0)
    }
}
