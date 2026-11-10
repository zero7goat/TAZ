package com.zero7goat.taz.core.security

import android.os.SystemClock
import com.zero7goat.taz.core.settings.AutoLockTimeout
import com.zero7goat.taz.core.settings.SettingsRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LockState { Loading, Locked, Unlocked }

sealed interface UnlockResult {
    data object Success : UnlockResult
    data class Wrong(val attemptsBeforeDelay: Int) : UnlockResult
    data class LockedOut(val remainingMillis: Long) : UnlockResult

    /** The stored passcode cannot be verified on this device. */
    data object Unreadable : UnlockResult
}

/**
 * The single source of truth for whether TAZ is locked. The app lock is on exactly when a
 * passcode has been set. The screens ask this class; nothing else decides.
 *
 * Passcodes arrive as char arrays and are wiped as soon as they have been used. Nothing here
 * logs, and no passcode or hash ever leaves this class.
 */
class AppLockManager(
    private val store: PasscodeStore,
    settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val monotonicClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() }
) {

    private val _state = MutableStateFlow(LockState.Loading)
    val state: StateFlow<LockState> = _state.asStateFlow()

    /** True while a passcode is set (the app lock is on). */
    val hasPasscode: StateFlow<Boolean> =
        store.hasPasscode.stateIn(scope, SharingStarted.Eagerly, false)

    @Volatile
    private var timeout: AutoLockTimeout = AutoLockTimeout.MINUTE_1

    @Volatile
    private var lastInteraction: Long = monotonicClock()

    @Volatile
    private var backgroundedAt: Long? = null

    private val mutex = Mutex()

    init {
        scope.launch { settings.settings.collect { timeout = it.autoLockTimeout } }
        scope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) { store.load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PasscodeStore.Loaded.Unreadable
            }
            // A passcode that exists (even an unreadable one) means locked at start.
            _state.value = if (loaded is PasscodeStore.Loaded.None) LockState.Unlocked else LockState.Locked
        }
    }

    // ---------- when to lock ----------

    fun onUserInteraction() {
        lastInteraction = monotonicClock()
    }

    fun onAppStopped() {
        backgroundedAt = monotonicClock()
    }

    fun onAppStarted() {
        val away = backgroundedAt?.let { monotonicClock() - it }
        backgroundedAt = null
        lastInteraction = monotonicClock()
        if (away != null && isUnlockedWithLock() && LockPolicy.shouldLockOnReturn(timeout, away)) lockNow()
    }

    /** Called about once a second while the app is visible. */
    fun checkInactivity() {
        if (isUnlockedWithLock() &&
            LockPolicy.shouldLockForInactivity(timeout, monotonicClock() - lastInteraction)
        ) {
            lockNow()
        }
    }

    fun lockNow() {
        if (hasPasscode.value) _state.value = LockState.Locked
    }

    private fun isUnlockedWithLock() = _state.value == LockState.Unlocked && hasPasscode.value

    // ---------- unlocking ----------

    /** Called after a successful biometric prompt. Only valid while a passcode exists. */
    fun unlockWithBiometric() {
        if (hasPasscode.value) markUnlocked()
    }

    suspend fun unlock(passcode: CharArray): UnlockResult {
        val result = verify(passcode)
        if (result == UnlockResult.Success) markUnlocked()
        return result
    }

    /** The wall-clock time until which attempts are refused, or 0. */
    suspend fun lockoutUntil(): Long = store.readFailures().lockedUntilMillis.takeIf { it > wallClock() } ?: 0L

    private fun markUnlocked() {
        lastInteraction = monotonicClock()
        _state.value = LockState.Unlocked
    }

    /** Checks a passcode, counting failures and enforcing delays. Does not change the lock state. */
    suspend fun verify(passcode: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                val failures = store.readFailures()
                val now = wallClock()
                if (failures.lockedUntilMillis > now) {
                    return@withLock UnlockResult.LockedOut(failures.lockedUntilMillis - now)
                }
                when (val loaded = store.load()) {
                    PasscodeStore.Loaded.None -> UnlockResult.Success
                    PasscodeStore.Loaded.Unreadable -> UnlockResult.Unreadable
                    is PasscodeStore.Loaded.Present -> {
                        if (PasscodeHasher.matches(passcode, loaded.salt, loaded.iterations, loaded.hash)) {
                            store.writeFailures(0, 0L)
                            UnlockResult.Success
                        } else {
                            val count = failures.count + 1
                            val delay = LockoutPolicy.delayMillis(count)
                            store.writeFailures(count, if (delay > 0) now + delay else 0L)
                            if (delay > 0) {
                                UnlockResult.LockedOut(delay)
                            } else {
                                UnlockResult.Wrong(LockoutPolicy.attemptsBeforeDelay(count))
                            }
                        }
                    }
                }
            } finally {
                passcode.fill('\u0000')
            }
        }
    }

    // ---------- managing the passcode ----------

    /** Sets the first passcode (turns the app lock on). Returns false if [passcode] is not valid. */
    suspend fun setPasscode(passcode: CharArray): Boolean = withContext(Dispatchers.Default) {
        try {
            if (!PasscodeRules.isValid(passcode)) return@withContext false
            saveNew(passcode)
            true
        } finally {
            passcode.fill('\u0000')
        }
    }

    /** Replaces the passcode after verifying the current one. */
    suspend fun changePasscode(current: CharArray, new: CharArray): UnlockResult {
        if (!PasscodeRules.isValid(new)) {
            current.fill('\u0000')
            new.fill('\u0000')
            return UnlockResult.Wrong(LockoutPolicy.FREE_ATTEMPTS)
        }
        val result = verify(current)
        try {
            if (result == UnlockResult.Success) withContext(Dispatchers.Default) { saveNew(new) }
        } finally {
            new.fill('\u0000')
        }
        return result
    }

    /** Turns the app lock off after verifying the passcode. */
    suspend fun removePasscode(passcode: CharArray): UnlockResult {
        val result = verify(passcode)
        if (result == UnlockResult.Success) {
            store.clear()
            _state.value = LockState.Unlocked
        }
        return result
    }

    private suspend fun saveNew(passcode: CharArray) {
        val salt = PasscodeHasher.newSalt()
        val iterations = PasscodeHasher.DEFAULT_ITERATIONS
        val hash = PasscodeHasher.hash(passcode, salt, iterations)
        try {
            store.save(salt, iterations, hash)
        } finally {
            hash.fill(0)
        }
    }
}
