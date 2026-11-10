package com.zero7goat.taz.core.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zero7goat.taz.core.security.AppLockManager
import com.zero7goat.taz.core.security.LockState

/**
 * Shows the lock screen instead of [content] while locked, for every screen alike (Notes,
 * Passwords, Files, Settings and their editors), so no screen can be reached around the lock.
 * Screen state (selected tab, scroll positions) survives a lock; [onLocked] lets sensitive state
 * (decrypted credentials) be wiped at the moment the app locks.
 */
@Composable
fun AppLockGate(
    lock: AppLockManager,
    biometricEnabled: Boolean,
    onLocked: () -> Unit,
    content: @Composable () -> Unit
) {
    val state by lock.state.collectAsStateWithLifecycle()
    val stateHolder = rememberSaveableStateHolder()

    LaunchedEffect(state) {
        if (state == LockState.Locked) onLocked()
    }

    if (state == LockState.Loading) {
        // First moments after launch: nothing to animate yet, and no fade-in delaying the app.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    } else {
        // Locking is instant, so nothing sensitive lingers while fading out; unlocking fades in gently.
        // (The first state shown by the Crossfade appears immediately, with no animation.)
        Crossfade(
            targetState = state,
            animationSpec = if (state == LockState.Locked) snap() else tween(180),
            label = "lock"
        ) { current ->
            when (current) {
                LockState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                LockState.Locked -> LockScreen(lock = lock, biometricEnabled = biometricEnabled)
                LockState.Unlocked -> stateHolder.SaveableStateProvider("app") { content() }
            }
        }
    }
}
