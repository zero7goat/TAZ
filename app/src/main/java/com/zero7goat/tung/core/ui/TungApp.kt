package com.zero7goat.tung.core.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.tung.core.navigation.AppNavigation
import com.zero7goat.tung.core.security.AppLockManager
import com.zero7goat.tung.core.settings.AppSettings
import com.zero7goat.tung.core.settings.SettingsRepository
import com.zero7goat.tung.features.passwords.presentation.PasswordsViewModel
import com.zero7goat.tung.ui.theme.TUNGTheme
import com.zero7goat.tung.ui.theme.isDark

/**
 * The root of the UI: applies the saved theme, font and text size, then shows either the lock
 * screen or the app.
 */
@Composable
fun TungApp(settingsRepository: SettingsRepository, lock: AppLockManager) {
    val loaded: AppSettings? by produceState<AppSettings?>(null) {
        settingsRepository.settings.collect { value = it }
    }
    val settings = loaded
    if (settings == null) {
        // Settings are read from disk in a few milliseconds; show nothing rather than flash the wrong theme.
        Box(Modifier.fillMaxSize())
    } else {
        TungAppContent(settings = settings, lock = lock)
    }
}

@Composable
private fun TungAppContent(settings: AppSettings, lock: AppLockManager) {
    val isDark = settings.themeMode.isDark(isSystemInDarkTheme())
    SystemBarsEffect(isDark)

    val baseDensity = LocalDensity.current
    val scaledDensity = Density(baseDensity.density, baseDensity.fontScale * settings.fontSize.scale)
    val passwordsViewModel: PasswordsViewModel =
        viewModel(factory = PasswordsViewModel.factory(LocalContext.current.applicationContext))

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        TUNGTheme(themeMode = settings.themeMode, fontFamily = AppFonts.familyFor(settings.fontKey)) {
            AppLockGate(
                lock = lock,
                biometricEnabled = settings.biometricEnabled,
                // Locking drops any decrypted login that was open on screen.
                onLocked = { passwordsViewModel.closeEditor() }
            ) {
                AppNavigation()
            }
        }
    }
}

/** Keeps the status and navigation bar icons readable when the chosen theme differs from the system's. */
@Composable
private fun SystemBarsEffect(isDark: Boolean) {
    val activity = LocalContext.current.findFragmentActivity() as? ComponentActivity
    DisposableEffect(activity, isDark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { isDark },
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { isDark }
        )
        onDispose { }
    }
}
