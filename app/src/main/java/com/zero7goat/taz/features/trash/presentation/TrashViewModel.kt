package com.zero7goat.taz.features.trash.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zero7goat.taz.features.trash.data.TrashRepository
import com.zero7goat.taz.features.trash.domain.TrashItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [items] is null while loading, then the trash contents. */
class TrashViewModel(private val repository: TrashRepository) : ViewModel() {

    val items: StateFlow<List<TrashItem>?> = repository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun restore(item: TrashItem) {
        viewModelScope.launch { repository.restore(item) }
    }

    fun deleteForever(item: TrashItem) {
        viewModelScope.launch { repository.purge(item) }
    }

    fun emptyTrash() {
        viewModelScope.launch { repository.emptyTrash() }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TrashViewModel(TrashRepository.create(appContext)) as T
            }
        }
    }
}
