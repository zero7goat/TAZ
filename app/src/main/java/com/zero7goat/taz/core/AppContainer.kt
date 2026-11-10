package com.zero7goat.taz.core

import android.content.Context
import com.zero7goat.taz.core.security.AppLockManager
import com.zero7goat.taz.core.security.ClipboardGuard
import com.zero7goat.taz.core.security.PasscodeStore
import com.zero7goat.taz.core.settings.SettingsDataStore
import com.zero7goat.taz.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The few app-wide objects: settings and the app lock. One instance each per process, created on
 * first use. (A small holder instead of a dependency-injection framework.)
 */
object AppContainer {

    private val lock = Any()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var settingsRepository: SettingsRepository? = null

    @Volatile
    private var lockManager: AppLockManager? = null

    fun settings(context: Context): SettingsRepository =
        settingsRepository ?: synchronized(lock) {
            settingsRepository ?: SettingsRepository(SettingsDataStore(context.applicationContext)).also { repository ->
                settingsRepository = repository
                // Keep the clipboard-clear delay in step with the setting.
                appScope.launch {
                    repository.settings.collect { ClipboardGuard.clearAfterSeconds = it.clipboardClearTimeout.seconds }
                }
            }
        }

    fun lockManager(context: Context): AppLockManager =
        lockManager ?: synchronized(lock) {
            lockManager ?: AppLockManager(
                store = PasscodeStore(context.applicationContext),
                settings = settings(context),
                scope = appScope
            ).also { lockManager = it }
        }
}
