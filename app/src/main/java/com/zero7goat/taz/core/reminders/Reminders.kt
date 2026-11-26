package com.zero7goat.taz.core.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zero7goat.taz.R
import com.zero7goat.taz.TazActivity
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import com.zero7goat.taz.core.security.VaultMode
import com.zero7goat.taz.core.settings.FeaturePrefs
import com.zero7goat.taz.features.passwords.data.CredentialRepository
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Local reminders: note reminders at the time the user set, and a daily check for documents and
 * cards that are about to expire and passwords that are getting old. Everything is computed on
 * the device by WorkManager; notifications show no details on the lock screen.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    private const val DAILY = "taz-daily-check"
    private const val NOTE = "taz-note-reminder"

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Starts the daily check (idempotent) and schedules the next note reminder. */
    fun scheduleCheck(context: Context) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyCheckWorker>(1, TimeUnit.DAYS).build()
        )
        scheduleNextNote(context)
    }

    /** Wakes up at the soonest note reminder. */
    fun scheduleNextNote(context: Context) {
        val app = context.applicationContext
        val work = WorkManager.getInstance(app)
        Thread {
            // Only the real vault schedules reminders.
            if (VaultMode.isDecoy) return@Thread
            val next = try {
                TazDatabase.getInstance(app).openHelper.readableDatabase
                    .query("SELECT MIN(remindAt) FROM notes WHERE deletedAt IS NULL AND remindAt IS NOT NULL")
                    .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            } catch (e: Exception) {
                null
            }
            if (next == null) {
                work.cancelUniqueWork(NOTE)
            } else {
                val delay = max(0L, next - System.currentTimeMillis())
                work.enqueueUniqueWork(
                    NOTE, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<NoteReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
                )
            }
        }.start()
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Note reminders, expiry dates and old passwords"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                }
            )
        }
    }

    /** Shows a notification whose text is hidden on the lock screen ("TAZ reminder" only). */
    fun notify(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, TazActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_lock)
            .setContentTitle("TAZ reminder")
            .setContentText("Unlock to see it")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission was withdrawn: nothing to show.
        }
    }
}

/** Shows the note reminders that are due, then schedules the next one. */
class NoteReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (VaultMode.isDecoy) return Result.success()
        val dao = TazDatabase.getInstance(app).noteDao()
        // A minute of slack, so a reminder never waits for the next run because of timing.
        dao.remindersDue(System.currentTimeMillis() + 60_000L).forEach { note ->
            val title = if (note.isLocked) "Reminder" else note.title.ifBlank { "Note reminder" }
            val text = if (note.isLocked) "A locked note in TAZ has a reminder." else note.description.ifBlank { "Open TAZ to see your note." }
            Reminders.notify(app, ("note:" + note.id).hashCode(), title, text)
            dao.setRemindAt(note.id, null)
        }
        Reminders.scheduleNextNote(app)
        return Result.success()
    }
}

/** Once a day: expiry dates coming up, and passwords older than the chosen age. */
class DailyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (VaultMode.isDecoy) return Result.success()
        val prefs = FeaturePrefs(app)
        val settings = prefs.current()
        val logins = try {
            CredentialRepository(TazDatabase.getInstance(app).credentialDao(), KeystoreFieldCipher()).decryptAllForBackup().first
        } catch (e: Exception) {
            return Result.retry()
        }
        val now = System.currentTimeMillis()
        val due = ReminderRules.expiring(logins, now, settings.expiryReminderDays)
            .filterNot { it.key in settings.notifiedKeys }
        due.forEach { item ->
            Reminders.notify(app, item.key.hashCode(), "${item.label} expires soon", "${item.name}: ${item.whenText}")
        }
        val old = ReminderRules.oldPasswords(logins, now, settings.passwordAgeMonths)
        val oldKey = ReminderRules.oldPasswordsKey(old, now)
        if (old.isNotEmpty() && oldKey !in settings.notifiedKeys) {
            Reminders.notify(
                app, "old-passwords".hashCode(),
                "${old.size} ${if (old.size == 1) "password is" else "passwords are"} getting old",
                "Consider changing: " + old.take(5).joinToString(", ") + if (old.size > 5) ", …" else ""
            )
        }
        prefs.addNotified(due.map { it.key } + listOfNotNull(oldKey.takeIf { old.isNotEmpty() }))
        Reminders.scheduleNextNote(app)
        return Result.success()
    }
}
