package com.zero7goat.taz.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.zero7goat.taz.core.security.AppLockManager
import com.zero7goat.taz.core.security.AppReset
import com.zero7goat.taz.core.security.ClipboardGuard
import com.zero7goat.taz.core.security.PasscodeStore
import com.zero7goat.taz.core.settings.FeaturePrefs
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

    @Volatile
    private var lockOnScreenOff = false

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
            lockManager ?: run {
                val app = context.applicationContext
                val features = FeaturePrefs(app)
                AppLockManager(
                    store = PasscodeStore(app),
                    settings = settings(app),
                    scope = appScope,
                    wipeAfterAttempts = { features.current().wipeAfterAttempts },
                    onWipe = {
                        // "Erase after too many wrong passcodes": everything goes, then TAZ starts empty.
                        AppReset.eraseEverything(app)
                        AppReset.restart(app)
                    }
                ).also { manager ->
                    lockManager = manager
                    watchScreenOff(app, manager, features)
                }
            }
        }

    /** Locks TAZ the moment the screen turns off, when that option is on. */
    private fun watchScreenOff(app: Context, manager: AppLockManager, features: FeaturePrefs) {
        appScope.launch { features.settings.collect { lockOnScreenOff = it.lockOnScreenOff } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF && lockOnScreenOff) manager.lockNow()
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
