package com.zero7goat.tung.core.settings

/** Font key meaning "use the system font". Bundled fonts get their own keys later. */
const val DEFAULT_FONT_KEY = "system"

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** App-wide text size steps. [scale] is a multiplier for the base text sizes. */
enum class FontSize(val scale: Float) {
    SMALL(0.9f),
    DEFAULT(1.0f),
    LARGE(1.15f),
    EXTRA_LARGE(1.3f)
}

/** How long the app may stay in the background before locking. [seconds] is null for "never". */
enum class AutoLockTimeout(val seconds: Int?) {
    IMMEDIATELY(0),
    SECONDS_30(30),
    MINUTE_1(60),
    MINUTES_5(300),
    MINUTES_15(900),
    NEVER(null)
}

/** How long a copied password stays on the clipboard before it is cleared. */
enum class ClipboardClearTimeout(val seconds: Int) {
    SECONDS_10(10),
    SECONDS_30(30),
    MINUTE_1(60),
    MINUTES_2(120)
}

/**
 * Non-sensitive app preferences. Passwords, passcodes, keys and anything secret
 * must never be added here.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontSize: FontSize = FontSize.DEFAULT,
    val fontKey: String = DEFAULT_FONT_KEY,
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.MINUTE_1,
    val biometricEnabled: Boolean = false,
    val clipboardClearTimeout: ClipboardClearTimeout = ClipboardClearTimeout.SECONDS_30,
    /** Hides TUNG in the recent-apps preview and blocks screenshots (FLAG_SECURE). */
    val hideInRecents: Boolean = true
)
