package com.zero7goat.taz.core.security

import android.view.Window
import android.view.WindowManager

/**
 * Decides whether the window is marked FLAG_SECURE (no screenshots, blank recent-apps preview).
 * It is secure when the "hide in recent apps" setting is on, or while any sensitive screen
 * (such as a saved login) has asked for it. Call from the main thread only.
 */
object SecureWindow {

    private var settingOn = true
    private var screenRequests = 0

    fun setSetting(window: Window, on: Boolean) {
        settingOn = on
        apply(window)
    }

    /** Re-applies the current state to a new window (after the activity is recreated). */
    fun refresh(window: Window) = apply(window)

    fun acquire(window: Window) {
        screenRequests++
        apply(window)
    }

    fun release(window: Window) {
        if (screenRequests > 0) screenRequests--
        apply(window)
    }

    private fun apply(window: Window) {
        if (settingOn || screenRequests > 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
