package com.zero7goat.taz.features.search.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import com.zero7goat.taz.features.search.data.SearchIndex
import com.zero7goat.taz.features.search.data.SearchIndexSource
import com.zero7goat.taz.features.search.domain.ResultType
import com.zero7goat.taz.features.search.domain.SearchFilters
import com.zero7goat.taz.features.search.domain.SearchHit
import com.zero7goat.taz.features.search.domain.VaultSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update

/** What the search screen shows. [index] is null while it is being built the first time. */
data class SearchUiState(
    val index: SearchIndex? = null,
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val hits: List<SearchHit> = emptyList(),
    /** Hit counts per type before the type filter, for the chips. */
    val countsByType: Map<ResultType, Int> = emptyMap()
) {
    val isLoading: Boolean get() = index == null
    val isIdle: Boolean get() = query.isBlank() && !filters.isActive
}

/**
 * Holds only the query and filters, never decrypted data: the results come from [results], a cold
 * flow that the screen collects while it is shown, so the decrypted index disappears with it.
 * The query is not put into saved instance state.
 */
class SearchViewModel(private val source: SearchIndexSource) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    /** What to show before the first results arrive: the current query and filters, still loading. */
    fun initialState(): SearchUiState = SearchUiState(query = _query.value, filters = _filters.value)

    /** The query typed before (kept across rotation, never in saved state). */
    fun currentQuery(): String = _query.value

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setFilters(transform: (SearchFilters) -> SearchFilters) = _filters.update(transform)

    fun toggleType(type: ResultType) = _filters.update {
        it.copy(types = if (type in it.types) it.types - type else setOf(type))
    }

    fun clearTypes() = _filters.update { it.copy(types = emptySet()) }

    fun resetFilters() = _filters.update { SearchFilters(types = it.types) }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun results(): Flow<SearchUiState> =
        combine(source.observe(), _query.debounce(80), _filters) { index, text, filter ->
            val now = System.currentTimeMillis()
            val all = VaultSearch.search(index.docs, text, filter.copy(types = emptySet()), now, index.parentOf)
            val hits = if (filter.types.isEmpty()) all else all.filter { it.doc.type in filter.types }
            SearchUiState(
                index = index,
                query = text,
                filters = filter,
                hits = hits,
                countsByType = all.groupingBy { it.doc.type }.eachCount()
            )
        }.flowOn(Dispatchers.Default)

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer { SearchViewModel(SearchIndexSource(TazDatabase.getInstance(appContext), KeystoreFieldCipher())) }
            }
        }
    }
}
