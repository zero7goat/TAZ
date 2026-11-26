package com.zero7goat.taz.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.zero7goat.taz.core.security.VaultMode

/**
 * Gives everything inside its own view models and screen state for the vault being shown. When
 * the vault switches (duress passcode), the old view models are cleared and every screen starts
 * fresh, so nothing loaded from one vault can appear in the other.
 *
 * Default factories and creation extras (saved state) come from the activity, so view models
 * that use SavedStateHandle keep working.
 */
@Composable
fun VaultScope(content: @Composable () -> Unit) {
    val generation by VaultMode.generation.collectAsStateWithLifecycle()
    val parent = LocalViewModelStoreOwner.current
    key(generation) {
        val owner = remember(parent) { VaultViewModelStoreOwner(parent as? HasDefaultViewModelProviderFactory) }
        DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
    }
}

private class VaultViewModelStoreOwner(
    private val parent: HasDefaultViewModelProviderFactory?
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {

    override val viewModelStore = ViewModelStore()

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = parent?.defaultViewModelProviderFactory ?: ViewModelProvider.NewInstanceFactory()

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras(parent?.defaultViewModelCreationExtras ?: CreationExtras.Empty).apply {
            // Saved state is looked up through the store that owns the view model: this one.
            set(VIEW_MODEL_STORE_OWNER_KEY, this@VaultViewModelStoreOwner)
        }
}
