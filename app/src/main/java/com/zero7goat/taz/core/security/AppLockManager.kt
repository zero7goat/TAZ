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

    /** The duress passcode: unlocks into the empty decoy vault. */
    data object Decoy : UnlockResult

    /** Too many wrong passcodes with "erase after" turned on: everything was erased. */
    data object Wiped : UnlockResult
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
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    /** How many wrong passcodes in a row erase everything; 0 = never. */
    private val wipeAfterAttempts: suspend () -> Int = { 0 },
    /** Erases all TAZ data (see AppReset). */
    private val onWipe: suspend () -> Unit = {}
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
        if (hasPasscode.value) {
            _state.value = LockState.Locked
            _unlockedItems.value = emptySet()
        }
    }

    private fun isUnlockedWithLock() = _state.value == LockState.Unlocked && hasPasscode.value

    /**
     * For the autofill service, which runs while TAZ itself is in the background: applies the
     * auto-lock rule as if the user had just returned (locking if TAZ has been away too long) and
     * says whether logins may be filled without asking for the passcode. Still loading counts as
     * locked, so a freshly started process never fills before the lock state is known.
     */
    fun isUnlockedForAutofill(): Boolean {
        if (_state.value != LockState.Unlocked) return false
        if (!hasPasscode.value) return true
        val away = backgroundedAt?.let { monotonicClock() - it }
        if (away != null && LockPolicy.shouldLockOnReturn(timeout, away)) {
            lockNow()
            return false
        }
        return true
    }

    // ---------- items with their own lock ----------

    private val _unlockedItems = MutableStateFlow<Set<String>>(emptySet())

    /** Items (keys like "LOGIN:id") opened with the passcode since TAZ last locked. */
    val unlockedItems: StateFlow<Set<String>> = _unlockedItems.asStateFlow()

    fun markItemUnlocked(key: String) {
        _unlockedItems.value = _unlockedItems.value + key
    }

    // ---------- unlocking ----------

    /** Called after a successful biometric prompt. Only valid while a passcode exists. */
    fun unlockWithBiometric() {
        if (hasPasscode.value) {
            VaultMode.enter(decoy = false)
            markUnlocked()
        }
    }

    /**
     * Unlocks with [passcode]. [UnlockResult.Success] opens the real vault and
     * [UnlockResult.Decoy] (the duress passcode) the decoy vault; the caller switches vaults.
     */
    suspend fun unlock(passcode: CharArray): UnlockResult {
        val result = verify(passcode)
        if (result == UnlockResult.Success || result == UnlockResult.Decoy) {
            // Switch vaults before anything is shown, so no frame of the other vault appears.
            VaultMode.enter(decoy = result == UnlockResult.Decoy)
            markUnlocked()
        }
        return result
    }

    /**
     * Checks the passcode for a sensitive action inside the app (opening a locked item, exporting).
     * In the decoy vault the duress passcode is the one that works, so nothing gives the decoy away.
     */
    suspend fun verifyForAction(passcode: CharArray): UnlockResult {
        val result = verify(passcode)
        return when {
            result == UnlockResult.Success && !VaultMode.isDecoy -> UnlockResult.Success
            result == UnlockResult.Decoy && VaultMode.isDecoy -> UnlockResult.Success
            result == UnlockResult.Success || result == UnlockResult.Decoy -> UnlockResult.Wrong(LockoutPolicy.FREE_ATTEMPTS)
            else -> result
        }
    }

    /** The wall-clock time until which attempts are refused, or 0. */
    suspend fun lockoutUntil(): Long = store.readFailures().lockedUntilMillis.takeIf { it > wallClock() } ?: 0L

    private fun markUnlocked() {
        lastInteraction = monotonicClock()
        _state.value = LockState.Unlocked
    }

    /**
     * Checks a passcode, counting failures and enforcing delays. Does not change the lock state.
     * Returns [UnlockResult.Decoy] for the duress passcode, and [UnlockResult.Wiped] once "erase
     * after N wrong passcodes" is on and that many wrong ones were entered in a row.
     */
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
                        } else if (matchesDuress(passcode)) {
                            store.writeFailures(0, 0L)
                            UnlockResult.Decoy
                        } else {
                            val count = failures.count + 1
                            val wipeAt = wipeAfterAttempts()
                            if (wipeAt > 0 && count >= wipeAt) {
                                onWipe()
                                return@withLock UnlockResult.Wiped
                            }
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

    private suspend fun matchesDuress(passcode: CharArray): Boolean {
        val duress = store.loadDuress() as? PasscodeStore.Loaded.Present ?: return false
        return PasscodeHasher.matches(passcode, duress.salt, duress.iterations, duress.hash)
    }

    // ---------- duress passcode ----------

    val hasDuress: StateFlow<Boolean> = store.hasDuress.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Sets the duress passcode after checking the real one. It must be a valid passcode and differ
     * from the real one. Returns the result of checking [current] (Wrong if [duress] is not usable).
     */
    suspend fun setDuress(current: CharArray, duress: CharArray): UnlockResult {
        if (!PasscodeRules.isValid(duress)) {
            current.fill('\u0000')
            duress.fill('\u0000')
            return UnlockResult.Wrong(LockoutPolicy.FREE_ATTEMPTS)
        }
        val copy = duress.copyOf()
        duress.fill('\u0000')
        val result = verify(current)
        try {
            if (result != UnlockResult.Success) return result
            val real = store.load() as? PasscodeStore.Loaded.Present ?: return UnlockResult.Unreadable
            if (PasscodeHasher.matches(copy.copyOf(), real.salt, real.iterations, real.hash)) {
                return UnlockResult.Wrong(LockoutPolicy.FREE_ATTEMPTS)
            }
            withContext(Dispatchers.Default) {
                val salt = PasscodeHasher.newSalt()
                val hash = PasscodeHasher.hash(copy.copyOf(), salt, PasscodeHasher.DEFAULT_ITERATIONS)
                try {
                    store.saveDuress(salt, PasscodeHasher.DEFAULT_ITERATIONS, hash)
                } finally {
                    hash.fill(0)
                }
            }
            return UnlockResult.Success
        } finally {
            copy.fill('\u0000')
        }
    }

    suspend fun removeDuress() = store.clearDuress()

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
