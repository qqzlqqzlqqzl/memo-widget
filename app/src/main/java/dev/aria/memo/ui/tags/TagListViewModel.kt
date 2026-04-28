package dev.aria.memo.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.tag.TagIndexer
import dev.aria.memo.data.tag.TagNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Read-only UI state for [dev.aria.memo.ui.tags.TagListScreen]. */
data class TagListUiState(
    /** Root of the nested-tag tree. Empty when no tags exist yet. */
    val root: TagNode = TagNode(name = "", children = emptyList(), entries = emptyList()),
)

class TagListViewModel(
    private val repository: MemoRepository,
) : ViewModel() {

    // Bug-1 H11 fix (#115): combine day-files + single-notes,让 TagIndexer 同时
    // 扫两套数据源,#tag 在 single-note 里也能被发现。
    val state: StateFlow<TagListUiState> = combine(
        repository.observeNotes(),
        ServiceLocator.singleNoteRepo.observeAll(),
    ) { files, singleNotes ->
        TagListUiState(root = TagIndexer.indexAll(files, singleNotes))
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TagListUiState())

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(TagListViewModel::class.java)) {
                    "Unknown ViewModel class: $modelClass"
                }
                return TagListViewModel(ServiceLocator.repository) as T
            }
        }
    }
}
