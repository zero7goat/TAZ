package com.zero7goat.tung.core.security

import com.zero7goat.tung.core.settings.AutoLockTimeout

/** When the app should lock itself. Pure functions so the rules are easy to test. */
object LockPolicy {

    /** On returning to the app after [awayMs] in the background. */
    fun shouldLockOnReturn(timeout: AutoLockTimeout, awayMs: Long): Boolean {
        val seconds = timeout.seconds ?: return false // NEVER: only a fresh start locks
        return awayMs >= seconds * 1000L // IMMEDIATELY has 0 seconds, so any absence locks
    }

    /**
     * While the app stays open with no touches for [idleMs]. IMMEDIATELY and NEVER do not use
     * inactivity: they lock on leaving the app, or only at start.
     */
    fun shouldLockForInactivity(timeout: AutoLockTimeout, idleMs: Long): Boolean {
        val seconds = timeout.seconds ?: return false
        if (seconds == 0) return false
        return idleMs >= seconds * 1000L
    }
}

/** How long to refuse passcode attempts after repeated failures. Short at first, then longer. */
object LockoutPolicy {

    const val FREE_ATTEMPTS = 4

    fun delayMillis(failedAttempts: Int): Long = when {
        failedAttempts <= FREE_ATTEMPTS -> 0L
        failedAttempts == 5 -> 30_000L
        failedAttempts == 6 -> 60_000L
        failedAttempts == 7 -> 5 * 60_000L
        else -> 15 * 60_000L
    }

    /** Attempts left before the first delay starts (never negative). */
    fun attemptsBeforeDelay(failedAttempts: Int): Int = (FREE_ATTEMPTS - failedAttempts).coerceAtLeast(0)
}

/** What counts as a valid passcode: 4 to 16 digits. */
object PasscodeRules {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 16

    fun isValid(passcode: CharArray): Boolean =
        passcode.size in MIN_LENGTH..MAX_LENGTH && passcode.all { it in '0'..'9' }
}
