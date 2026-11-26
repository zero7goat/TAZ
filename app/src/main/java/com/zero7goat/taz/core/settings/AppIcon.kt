package com.zero7goat.taz.core.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Switches the home-screen icon and name between TAZ and a disguise, by enabling exactly one of
 * the launcher aliases in the manifest. The app itself, its data and its lock are unchanged.
 * Launchers can take a few seconds to show the new icon.
 */
object AppIcon {

    private fun aliasOf(disguise: AppDisguise) = when (disguise) {
        // DO NOT RENAME: the launcher component every version since 1.0 used.
        AppDisguise.NONE -> "com.zero7goat.taz.MainActivity"
        AppDisguise.CALCULATOR -> "com.zero7goat.taz.LauncherCalculator"
        AppDisguise.NOTES -> "com.zero7goat.taz.LauncherNotes"
        AppDisguise.WEATHER -> "com.zero7goat.taz.LauncherWeather"
    }

    fun apply(context: Context, chosen: AppDisguise) {
        val pm = context.packageManager
        // Enable the new one first, so there is never a moment without any launcher entry.
        set(pm, context, aliasOf(chosen), true)
        AppDisguise.entries.filter { it != chosen }.forEach { set(pm, context, aliasOf(it), false) }
    }

    private fun set(pm: PackageManager, context: Context, alias: String, enabled: Boolean) {
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, alias),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
