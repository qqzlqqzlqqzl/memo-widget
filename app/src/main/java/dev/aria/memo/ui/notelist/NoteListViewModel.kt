package dev.aria.memo.ui.notelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.MemoEntry
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.SingleNoteRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * Legacy day-file grouping. Kept for backward compatibility with the original
 * note-list rendering and existing tests — new single-note rows are surfaced
 * via [NoteListUiItem.SingleNote] alongside these in [NoteListUiState.items].
 */
data class DayGroup(
    val date: LocalDate,
    val entries: List<MemoEntry>,
    val dirty: Boolean,
    val path: String,
    val pinned: Boolean,
)

/**
 * Sum type over the two note formats that coexist in P6.1: legacy day-files
 * (one file per date, multiple `## HH:MM` entries inside) and new single-note
 * files (one file per entry under `notes/`). The list screen renders both
 * shapes through a single LazyColumn; the repository layer stays segregated.
 */
sealed interface NoteListUiItem {
    val path: String
    val pinned: Boolean

    data class LegacyDay(val group: DayGroup) : NoteListUiItem {
        override val path: String = group.path
        override val pinned: Boolean = group.pinned
    }

    data class SingleNote(
        val uid: String,
        override val path: String,
        val title: String,
        /** Body preview — up to 120 chars, front-matter stripped. */
        val preview: String,
        val date: LocalDate,
        val time: LocalTime,
        override val pinned: Boolean,
    ) : NoteListUiItem
}

data class NoteListUiState(
    val items: List<NoteListUiItem> = emptyList(),
    val query: String = "",
    val isRefreshing: Boolean = false,
) {
    /** Legacy day-file groups extracted from [items] — kept for older consumers. */
    val groups: List<DayGroup>
        get() = items.asSequence()
            .filterIsInstance<NoteListUiItem.LegacyDay>()
            .map { it.group }
            .toList()

    /**
     * Apply the free-text query to both shapes:
     *  - [NoteListUiItem.LegacyDay]: match by any entry body
     *  - [NoteListUiItem.SingleNote]: match by title or preview
     */
    val filtered: List<NoteListUiItem>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return items
            return items.mapNotNull { item ->
                when (item) {
                    is NoteListUiItem.LegacyDay -> {
                        val hits = item.group.entries.filter {
                            it.body.contains(q, ignoreCase = true)
                        }
                        if (hits.isEmpty()) null
                        else NoteListUiItem.LegacyDay(item.group.copy(entries = hits))
                    }
                    is NoteListUiItem.SingleNote -> {
                        val match = item.title.contains(q, ignoreCase = true) ||
                            item.preview.contains(q, ignoreCase = true)
                        if (match) item else null
                    }
                }
            }
        }

    /** Pinned items from the current [filtered] view, preserving input order. */
    val pinned: List<NoteListUiItem> get() = filtered.filter { it.pinned }

    /** Non-pinned items from the current [filtered] view, preserving input order. */
    val unpinned: List<NoteListUiItem> get() = filtered.filter { !it.pinned }
}

class NoteListViewModel(
    private val repository: MemoRepository,
    private val singleNoteRepo: SingleNoteRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _refreshing = MutableStateFlow(false)

    private val legacyGroups = repository.observeNotes()
    private val singleNotes = singleNoteRepo.observeAll()

    val state: StateFlow<NoteListUiState> =
        combine(legacyGroups, singleNotes, _query, _refreshing) { files, singles, q, r ->
            val legacyItems: List<NoteListUiItem> = files.map { f ->
                NoteListUiItem.LegacyDay(
                    DayGroup(
                        date = f.date,
                        entries = MemoRepository.parseEntries(f.content, f.date),
                        dirty = f.dirty,
                        path = f.path,
                        pinned = f.isPinned,
                    ),
                )
            }
            val singleItems: List<NoteListUiItem> = singles.map { s ->
                NoteListUiItem.SingleNote(
                    uid = s.uid,
                    path = s.filePath,
                    title = s.title,
                    preview = buildPreview(s.body),
                    date = s.date,
                    time = s.time,
                    pinned = s.isPinned,
                )
            }
            val merged = (legacyItems + singleItems).sortedWith(ITEM_ORDER)
            NoteListUiState(items = merged, query = q, isRefreshing = r)
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

    /**
     * Pin toggle dispatcher. Routes by path shape: anything under `notes/` is a
     * single-note (looked up by uid via the current state's items), everything
     * else falls through to [MemoRepository.togglePin].
     *
     * Fixes #46 (P6.1): if the UI's cached items haven't hydrated yet (rare,
     * requires a pin tap within ~100ms of cold start before combine's first
     * emission), fall back to [SingleNoteRepository.togglePinByPath] which hits
     * the DAO directly. Eliminates the previous silent no-op.
     */
    fun togglePin(path: String, pinned: Boolean) {
        viewModelScope.launch {
            if (path.startsWith("notes/")) {
                val uid = state.value.items
                    .asSequence()
                    .filterIsInstance<NoteListUiItem.SingleNote>()
                    .firstOrNull { it.path == path }
                    ?.uid
                if (uid != null) {
                    singleNoteRepo.togglePin(uid, pinned)
                } else {
                    singleNoteRepo.togglePinByPath(path, pinned)
                }
            } else {
                repository.togglePin(path, pinned)
            }
        }
    }

    companion object {
        /**
         * Sort: pinned first; within each bucket, newest (date,time) first.
         * For [NoteListUiItem.LegacyDay] the time is midnight so single-notes
         * from the same date sort above a legacy day-file with no time, which
         * matches "newer first" intent.
         */
        internal val ITEM_ORDER: Comparator<NoteListUiItem> =
            Comparator { a, b ->
                val pinCmp = b.pinned.compareTo(a.pinned)
                if (pinCmp != 0) return@Comparator pinCmp
                val ad = dateOf(a)
                val bd = dateOf(b)
                val dateCmp = bd.compareTo(ad)
                if (dateCmp != 0) return@Comparator dateCmp
                timeOf(b).compareTo(timeOf(a))
            }

        private fun dateOf(item: NoteListUiItem): LocalDate = when (item) {
            is NoteListUiItem.LegacyDay -> item.group.date
            is NoteListUiItem.SingleNote -> item.date
        }

        private fun timeOf(item: NoteListUiItem): LocalTime = when (item) {
            is NoteListUiItem.LegacyDay -> LocalTime.MIDNIGHT
            is NoteListUiItem.SingleNote -> item.time
        }

        private const val PREVIEW_MAX_CHARS = 120

        /**
         * Produce a short preview from a single-note body: strip a leading
         * `---\n...\n---` YAML block (tolerant parser, mirrors the shape the
         * pin-toggle codepath writes) then take the first [PREVIEW_MAX_CHARS]
         * characters of the remaining text. Deliberately does NOT depend on
         * the parallel `FrontMatterCodec` refactor.
         */
        internal fun buildPreview(body: String): String {
            val stripped = stripLeadingYaml(body)
            val collapsed = stripped.trim()
            return if (collapsed.length <= PREVIEW_MAX_CHARS) collapsed
            else collapsed.substring(0, PREVIEW_MAX_CHARS)
        }

        /**
         * Strip a leading `---\n...\n---` block if present. Intentionally
         * lenient — anything between the fences is dropped, so this works on
         * bodies written by either the `pinned:` writer or any future key/val
         * front-matter without coupling to that schema.
         */
        private fun stripLeadingYaml(text: String): String {
            val normalized = text.replace("\r\n", "\n")
            if (!normalized.startsWith("---\n")) return text
            val close = normalized.indexOf("\n---", 4)
            if (close < 0) return text
            var cut = close + "\n---".length
            while (cut < normalized.length && normalized[cut] == '\n') cut++
            return normalized.substring(cut)
        }

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(NoteListViewModel::class.java)) {
                    "Unknown ViewModel class: $modelClass"
                }
                return NoteListViewModel(
                    ServiceLocator.repository,
                    ServiceLocator.singleNoteRepo,
                ) as T
            }
        }
    }
}
