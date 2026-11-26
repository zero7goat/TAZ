package com.zero7goat.taz.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which vault this process is showing. The decoy vault is what the duress passcode opens: a
 * separate, empty database and file store, so the real data is neither shown nor touched.
 * The mode is kept in memory only; a restart always starts in the real vault (behind the lock).
 */
object VaultMode {
    @Volatile
    var isDecoy: Boolean = false
        private set

    private val _generation = MutableStateFlow(0)

    /**
     * Changes whenever the vault switches. The UI keys its screens and view models on it, so
     * nothing from one vault is ever shown in the other.
     */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Switches to the real ([decoy] false) or decoy vault. Does nothing if already there. */
    fun enter(decoy: Boolean) {
        if (isDecoy == decoy) return
        isDecoy = decoy
        _generation.value = _generation.value + 1
    }
}
