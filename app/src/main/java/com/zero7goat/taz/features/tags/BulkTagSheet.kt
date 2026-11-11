package com.zero7goat.taz.features.tags

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.tags.TagTarget
import com.zero7goat.taz.core.ui.TagPickerSheet

/**
 * Adds tags to several selected items at once. Tags they already have are kept; nothing is removed.
 * [onFinished] gets whether any tag was added.
 */
@Composable
fun BulkTagSheet(target: TagTarget, itemIds: Set<String>, onFinished: (Boolean) -> Unit) {
    val viewModel: TagsViewModel = viewModel(factory = TagsViewModel.factory(LocalContext.current.applicationContext))
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    TagPickerSheet(
        allTags = tags,
        initiallySelected = emptySet(),
        onCreate = { viewModel.createOrGet(it) },
        onDone = { chosen ->
            viewModel.addTags(target, itemIds, chosen)
            onFinished(chosen.isNotEmpty())
        },
        onDismiss = { onFinished(false) },
        title = if (itemIds.size == 1) "Add tags" else "Add tags to ${itemIds.size} items"
    )
}
