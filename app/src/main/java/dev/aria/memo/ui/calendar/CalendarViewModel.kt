package dev.aria.memo.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.EventRepository
import dev.aria.memo.data.MemoEntry
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.ics.EventExpander
import dev.aria.memo.data.ics.EventOccurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Aggregate of events + memos on a single day, ready for the day-sheet UI. */
data class DaySummary(
    val events: List<EventOccurrence>,
    val memos: List<MemoEntry>,
)

data class CalendarUiState(
    val selected: LocalDate = LocalDate.now(),
    val daySummary: DaySummary = DaySummary(emptyList(), emptyList()),
    val markedDates: Set<LocalDate> = emptySet(),
)

class CalendarViewModel(
    private val memoRepo: MemoRepository,
    private val eventRepo: EventRepository,
) : ViewModel() {

    private val _selected = MutableStateFlow(LocalDate.now())

    private val allEvents = eventRepo.observeAll()
    private val allNotes = memoRepo.observeNotes()

    // Fixes #31: split expansion from per-day filtering. The expansion window
    // is anchored on "today" (not on _selected), so switching selection no
    // longer triggers an O(events × span) re-expansion — only an O(events)
    // filter over the already-materialised occurrence list.
    private data class Expanded(
        val occurrences: List<EventOccurrence>,
        val markers: Set<LocalDate>,
    )

    private val expanded = combine(allEvents, allNotes) { events, notes ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val windowStartMs = today.minusDays(HISTORY_BUFFER_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEndMs = today.plusDays(FUTURE_HORIZON_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val occurrences = events.flatMap { EventExpander.expand(it, windowStartMs, windowEndMs, zone) }

        val markers = HashSet<LocalDate>()
        for (occ in occurrences) {
            var d = java.time.Instant.ofEpochMilli(occ.startEpochMs).atZone(zone).toLocalDate()
            val endDate = java.time.Instant.ofEpochMilli(occ.endEpochMs).atZone(zone).toLocalDate()
            while (!d.isAfter(endDate)) {
                markers.add(d)
                d = d.plusDays(1)
            }
        }
        for (n in notes) markers.add(n.date)

        Expanded(occurrences = occurrences, markers = markers)
    }.flowOn(Dispatchers.Default)

    // Fixes #7 (markers on Default) + P4 (RRULE expansion).
    val state: StateFlow<CalendarUiState> = combine(expanded, allNotes, _selected) { ex, notes, sel ->
        val zone = ZoneId.systemDefault()
        val dayStart = sel.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = sel.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEvents = ex.occurrences
            .filter { it.startEpochMs < dayEnd && it.endEpochMs >= dayStart }
            .sortedBy { it.startEpochMs }
        val dayMemos = notes.firstOrNull { it.date == sel }?.let {
            MemoRepository.parseEntries(it.content, it.date)
        } ?: emptyList()

        CalendarUiState(
            selected = sel,
            daySummary = DaySummary(events = dayEvents, memos = dayMemos),
            markedDates = ex.markers,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, CalendarUiState())

    fun selectDate(date: LocalDate) { _selected.value = date }

    /**
     * Double-tap guard for create/update/delete. [EventEditDialog]'s 保存 button
     * has no "saving" spinner state of its own, and `onSave` fires once per
     * tap — without this flag a fast double-tap would push two rows into Room
     * (each with a fresh UUID on create, or two parallel updates on edit) and
     * surface as duplicate calendar entries. Set when a CRUD coroutine starts,
     * cleared in its finally block so a retry after a thrown exception still
     * works.
     */
    @Volatile
    private var mutating: Boolean = false

    fun createEvent(
        summary: String,
        startMs: Long,
        endMs: Long,
        rrule: String?,
        reminderMinutesBefore: Int?,
        onDone: () -> Unit = {},
    ) {
        if (mutating) return
        mutating = true
        viewModelScope.launch {
            try {
                eventRepo.create(summary, startMs, endMs, rrule = rrule, reminderMinutesBefore = reminderMinutesBefore)
                onDone()
            } finally {
                mutating = false
            }
        }
    }

    fun updateEvent(
        uid: String,
        summary: String,
        startMs: Long,
        endMs: Long,
        rrule: String?,
        reminderMinutesBefore: Int?,
        onDone: () -> Unit = {},
    ) {
        if (mutating) return
        mutating = true
        viewModelScope.launch {
            try {
                eventRepo.update(uid, summary, startMs, endMs, rrule = rrule, reminderMinutesBefore = reminderMinutesBefore)
                onDone()
            } finally {
                mutating = false
            }
        }
    }

    fun deleteEvent(uid: String, onDone: () -> Unit = {}) {
        if (mutating) return
        mutating = true
        viewModelScope.launch {
            try {
                eventRepo.delete(uid)
                onDone()
            } finally {
                mutating = false
            }
        }
    }

    companion object {
        private const val HISTORY_BUFFER_DAYS = 30L
        private const val FUTURE_HORIZON_DAYS = 365L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CalendarViewModel::class.java)) {
                    "Unknown ViewModel class: $modelClass"
                }
                return CalendarViewModel(
                    memoRepo = ServiceLocator.repository,
                    eventRepo = ServiceLocator.eventRepo,
                ) as T
            }
        }
    }
}
