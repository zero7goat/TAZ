package com.zero7goat.taz

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zero7goat.taz.core.AppContainer
import com.zero7goat.taz.core.backup.BackupService
import com.zero7goat.taz.core.security.AppLockManager
import com.zero7goat.taz.core.security.SecureWindow
import com.zero7goat.taz.core.ui.TazApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A FragmentActivity (still a ComponentActivity) because BiometricPrompt needs one. It feeds
 * the app lock the events it needs: touches, leaving and returning to the app, and a once-a-second
 * check for inactivity while the app is open.
 */
class MainActivity : FragmentActivity() {

    private lateinit var lockManager: AppLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppContainer.settings(applicationContext)
        lockManager = AppContainer.lockManager(applicationContext)

        // Secure until the saved "hide in recent apps" setting says otherwise.
        SecureWindow.refresh(window)
        enableEdgeToEdge()

        lifecycleScope.launch {
            settings.settings
                .map { it.hideInRecents }
                .distinctUntilChanged()
                .collect { SecureWindow.setSetting(window, it) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(1_000)
                    lockManager.checkInactivity()
                }
            }
        }

        // Finishes or undoes a backup restore that was interrupted (for example, the app was killed).
        lifecycleScope.launch {
            try {
                BackupService.create(applicationContext).recoverInterruptedRestore()
            } catch (e: Exception) {
                // Left for the next start; the restore journal stays in place.
            }
        }

        setContent { TazApp(settingsRepository = settings, lock = lockManager) }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lockManager.onUserInteraction()
    }

    override fun onStart() {
        super.onStart()
        lockManager.onAppStarted()
    }

    override fun onStop() {
        // Rotating the screen also stops the activity; that is not leaving the app.
        if (!isChangingConfigurations) lockManager.onAppStopped()
        super.onStop()
    }
}
