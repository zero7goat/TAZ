package com.zero7goat.tung.core.security

import com.zero7goat.tung.core.settings.AutoLockTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {

    private val second = 1_000L

    @Test
    fun immediatelyLocksOnAnyReturn() {
        assertTrue(LockPolicy.shouldLockOnReturn(AutoLockTimeout.IMMEDIATELY, 0))
        assertTrue(LockPolicy.shouldLockOnReturn(AutoLockTimeout.IMMEDIATELY, 500))
    }

    @Test
    fun timedOptionsLockOnlyAfterTheTime() {
        assertFalse(LockPolicy.shouldLockOnReturn(AutoLockTimeout.SECONDS_30, 29 * second))
        assertTrue(LockPolicy.shouldLockOnReturn(AutoLockTimeout.SECONDS_30, 30 * second))
        assertFalse(LockPolicy.shouldLockOnReturn(AutoLockTimeout.MINUTE_1, 59 * second))
        assertTrue(LockPolicy.shouldLockOnReturn(AutoLockTimeout.MINUTE_1, 61 * second))
        assertFalse(LockPolicy.shouldLockOnReturn(AutoLockTimeout.MINUTES_5, 4 * 60 * second))
        assertTrue(LockPolicy.shouldLockOnReturn(AutoLockTimeout.MINUTES_15, 15 * 60 * second))
    }

    @Test
    fun neverNeverLocksOnReturnOrInactivity() {
        assertFalse(LockPolicy.shouldLockOnReturn(AutoLockTimeout.NEVER, Long.MAX_VALUE / 2))
        assertFalse(LockPolicy.shouldLockForInactivity(AutoLockTimeout.NEVER, Long.MAX_VALUE / 2))
    }

    @Test
    fun inactivityUsesTheTimeoutButNotForImmediately() {
        assertFalse(LockPolicy.shouldLockForInactivity(AutoLockTimeout.IMMEDIATELY, 10 * 60 * second))
        assertFalse(LockPolicy.shouldLockForInactivity(AutoLockTimeout.MINUTE_1, 59 * second))
        assertTrue(LockPolicy.shouldLockForInactivity(AutoLockTimeout.MINUTE_1, 60 * second))
        assertTrue(LockPolicy.shouldLockForInactivity(AutoLockTimeout.SECONDS_30, 31 * second))
    }

    @Test
    fun lockoutGrowsAfterTheFreeAttempts() {
        assertEquals(0L, LockoutPolicy.delayMillis(0))
        assertEquals(0L, LockoutPolicy.delayMillis(4))
        assertEquals(30_000L, LockoutPolicy.delayMillis(5))
        assertEquals(60_000L, LockoutPolicy.delayMillis(6))
        assertEquals(300_000L, LockoutPolicy.delayMillis(7))
        assertEquals(900_000L, LockoutPolicy.delayMillis(8))
        assertEquals(900_000L, LockoutPolicy.delayMillis(50))
    }

    @Test
    fun lockoutIsAlwaysTemporary() {
        // The longest wait is 15 minutes, never "forever", so nobody is locked out permanently.
        assertTrue((0..1000).all { LockoutPolicy.delayMillis(it) <= 15 * 60_000L })
    }

    @Test
    fun attemptsBeforeDelayCountsDown() {
        assertEquals(4, LockoutPolicy.attemptsBeforeDelay(0))
        assertEquals(1, LockoutPolicy.attemptsBeforeDelay(3))
        assertEquals(0, LockoutPolicy.attemptsBeforeDelay(4))
        assertEquals(0, LockoutPolicy.attemptsBeforeDelay(9))
    }

    @Test
    fun passcodeRules() {
        assertTrue(PasscodeRules.isValid("1234".toCharArray()))
        assertTrue(PasscodeRules.isValid("1234567890123456".toCharArray()))
        assertFalse(PasscodeRules.isValid("123".toCharArray()))
        assertFalse(PasscodeRules.isValid("12345678901234567".toCharArray()))
        assertFalse(PasscodeRules.isValid("12a4".toCharArray()))
        assertFalse(PasscodeRules.isValid("12 4".toCharArray()))
        assertFalse(PasscodeRules.isValid("".toCharArray()))
    }
}
