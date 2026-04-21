package dev.aria.memo.ui.notelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.MemoEntry
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DayGroup(
    val date: LocalDate,
    val entries: List<MemoEntry>,
    val dirty: Boolean,
    val path: String,
    val pinned: Boolean,
)

data class NoteListUiState(
    val groups: List<DayGroup> = emptyList(),
    val query: String = "",
    val isRefreshing: Boolean = false,
) {
    val filtered: List<DayGroup>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return groups
            return groups.mapNotNull { g ->
                val hits = g.entries.filter { it.body.contains(q, ignoreCase = true) }
                if (hits.isEmpty()) null else g.copy(entries = hits)
            }
        }

    /** Pinned groups, preserving the list's incoming ordering. */
    val pinned: List<DayGroup> get() = filtered.filter { it.pinned }

    /** Non-pinned groups, preserving the list's incoming ordering. */
    val unpinned: List<DayGroup> get() = filtered.filter { !it.pinned }
}

class NoteListViewModel(
    private val repository: MemoRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _refreshing = MutableStateFlow(false)

    private val groups = repository.observeNotes().map { files ->
        files.map { f ->
            DayGroup(
                date = f.date,
                entries = MemoRepository.parseEntries(f.content, f.date),
                dirty = f.dirty,
                path = f.path,
                pinned = f.isPinned,
            )
        }
    }

    val state: StateFlow<NoteListUiState> = combine(groups, _query, _refreshing) { g, q, r ->
        NoteListUiState(groups = g, query = q, isRefreshing = r)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NoteListUiState())

    init {
        // Kick an initial pull so the list has content on first open.
        repository.refreshNow()
    }

    fun onQueryChange(v: String) { _query.value = v }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        repository.refreshNow()
        viewModelScope.launch {
            delay(800)
            _refreshing.value = false
        }
    }

    fun togglePin(path: String, pinned: Boolean) {
        viewModelScope.launch {
            repository.togglePin(path, pinned)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(NoteListViewModel::class.java)) {
                    "Unknown ViewModel class: $modelClass"
                }
                return NoteListViewModel(ServiceLocator.repository) as T
            }
        }
    }
}
