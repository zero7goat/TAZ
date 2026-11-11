package com.zero7goat.taz.features.tags

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.tags.Tag
import com.zero7goat.taz.core.tags.TagRepository
import com.zero7goat.taz.core.tags.TagResult
import com.zero7goat.taz.core.tags.TagTarget
import com.zero7goat.taz.core.tags.TagUsageRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Tags for every screen: the list of tags, the tags of each item, and creating, renaming, deleting
 * and assigning them. Shared by notes, logins, files, search and the Tags screen.
 */
class TagsViewModel(private val repository: TagRepository) : ViewModel() {

    val tags: StateFlow<List<Tag>> =
        repository.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null until loaded, so the Tags screen can tell "loading" from "no tags". */
    val usage: StateFlow<List<TagUsageRow>?> =
        repository.observeUsage().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun tagsByItem(target: TagTarget): Flow<Map<String, List<Tag>>> = repository.observeTagsByItem(target)

    fun itemTags(target: TagTarget, itemId: String): Flow<List<Tag>> = repository.observeItemTags(target, itemId)

    /** Creates a tag (or finds the existing one with that name) and returns it. */
    suspend fun createOrGet(name: String): Tag? = (repository.createOrGet(name) as? TagResult.Ok)?.tag

    suspend fun rename(id: String, name: String): TagResult = repository.rename(id, name)

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun setItemTags(target: TagTarget, itemId: String, tagIds: Set<String>) {
        viewModelScope.launch { repository.setItemTags(target, itemId, tagIds) }
    }

    fun removeTag(target: TagTarget, itemId: String, tagId: String) {
        viewModelScope.launch { repository.removeTag(target, itemId, tagId) }
    }

    /** Adds tags to several items at once (multi-select), keeping the tags they already have. */
    fun addTags(target: TagTarget, itemIds: Collection<String>, tagIds: Set<String>) {
        val items = itemIds.toList()
        viewModelScope.launch { repository.addTags(target, items, tagIds) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer { TagsViewModel(TagRepository(TazDatabase.getInstance(appContext).tagDao())) }
            }
        }
    }
}
