package com.zero7goat.taz.core.security

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.zero7goat.taz.core.database.TazDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The recovery path for a forgotten or unreadable passcode, and what "erase after too many wrong
 * passcodes" does. TAZ has no accounts and no cloud, so a forgotten passcode cannot be recovered.
 * This erases everything TAZ stores on the device (both vaults, stored files, settings, passcodes,
 * scheduled backups and the encryption key) so the app can be used again from scratch. It is
 * deliberately explicit and irreversible. Backup files the user saved elsewhere are not touched.
 */
object AppReset {

    suspend fun eraseEverything(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            WorkManager.getInstance(app).cancelAllWork()
        } catch (e: Exception) {
            // Not initialised yet: nothing is scheduled.
        }
        app.getSystemService(NotificationManager::class.java)?.cancelAll()
        TazDatabase.closeAndForget()
        app.deleteDatabase(TazDatabase.DATABASE_NAME)
        app.deleteDatabase(TazDatabase.DECOY_DATABASE_NAME)
        File(app.filesDir, "vault").deleteRecursively()
        File(app.filesDir, "decoy").deleteRecursively()
        File(app.cacheDir, "share").deleteRecursively()
        deleteMasterKey()
        File(app.filesDir, "datastore").deleteRecursively()
    }

    /** Restarts the app in a fresh process, so no old state or cached key survives. */
    fun restart(activity: Activity) = restart(activity as Context)

    fun restart(context: Context) {
        val app = context.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)?.component
        if (launch != null) app.startActivity(Intent.makeRestartActivityTask(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Runtime.getRuntime().exit(0)
    }
}
