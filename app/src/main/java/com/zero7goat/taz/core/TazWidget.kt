package com.zero7goat.taz.core

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.zero7goat.taz.R
import com.zero7goat.taz.TazActivity

/**
 * The home-screen widget: New note, two-factor codes and Lock. It only starts TAZ (which asks for
 * the passcode as usual) or locks it; it never shows anything from the vault.
 */
class TazWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_taz).apply {
            setOnClickPendingIntent(R.id.widget_new_note, open(context, AppShortcuts.ACTION_NEW_NOTE, 1))
            setOnClickPendingIntent(R.id.widget_codes, open(context, AppShortcuts.ACTION_OPEN_CODES, 2))
            setOnClickPendingIntent(
                R.id.widget_lock,
                PendingIntent.getBroadcast(
                    context, 3, Intent(context, TazWidget::class.java).setAction(ACTION_LOCK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_LOCK) {
            val lock = AppContainer.lockManager(context.applicationContext)
            if (lock.hasPasscode.value) {
                lock.lockNow()
                Toast.makeText(context, "TAZ is locked", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Turn on the app lock in TAZ first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun open(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context, code,
            Intent(context, TazActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private companion object {
        const val ACTION_LOCK = "com.zero7goat.taz.action.WIDGET_LOCK"
    }
}
