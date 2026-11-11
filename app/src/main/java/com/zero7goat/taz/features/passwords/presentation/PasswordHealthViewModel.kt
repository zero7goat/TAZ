package com.zero7goat.taz.features.passwords.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import com.zero7goat.taz.features.passwords.data.CredentialRepository
import com.zero7goat.taz.features.passwords.domain.HealthInput
import com.zero7goat.taz.features.passwords.domain.HealthReport
import com.zero7goat.taz.features.passwords.domain.PasswordHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface HealthState {
    data object Loading : HealthState
    data class Ready(val report: HealthReport) : HealthState
    data object Failed : HealthState
}

/**
 * Runs the password health check. Passwords are decrypted only for the duration of the check, on
 * the device, and only names and ids are kept in the result.
 */
class PasswordHealthViewModel(private val repository: CredentialRepository) : ViewModel() {

    private val _state = MutableStateFlow<HealthState>(HealthState.Loading)
    val state: StateFlow<HealthState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = HealthState.Loading
        viewModelScope.launch {
            _state.value = try {
                val (details, unreadable) = repository.decryptAllForBackup()
                val report = withContext(Dispatchers.Default) {
                    // Entries without a password (Wi-Fi, cards, notes ... stored in fields) are not checked.
                    val inputs = details.filter { it.password.isNotEmpty() }
                        .map { HealthInput(it.id, it.serviceName, it.password, it.updatedAt) }
                    PasswordHealth.analyze(inputs, System.currentTimeMillis(), unreadable)
                }
                HealthState.Ready(report)
            } catch (e: Exception) {
                HealthState.Failed
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val database = TazDatabase.getInstance(appContext)
                    return PasswordHealthViewModel(
                        CredentialRepository(database.credentialDao(), KeystoreFieldCipher())
                    ) as T
                }
            }
        }
    }
}
