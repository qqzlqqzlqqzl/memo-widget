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

    // Fixes #7 (markers on Default) + P4 (RRULE expansion).
    val state: StateFlow<CalendarUiState> = combine(allEvents, allNotes, _selected) { events, notes, sel ->
        val zone = ZoneId.systemDefault()

        // Expand recurring events across a bounded window (past month →
        // +1 year) so we never materialise an unbounded stream.
        val windowStartMs = sel.minusDays(HISTORY_BUFFER_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEndMs = sel.plusDays(FUTURE_HORIZON_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val occurrences = events.flatMap { EventExpander.expand(it, windowStartMs, windowEndMs, zone) }

        val dayStart = sel.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = sel.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEvents = occurrences.filter { it.startEpochMs < dayEnd && it.endEpochMs >= dayStart }
            .sortedBy { it.startEpochMs }
        val dayMemos = notes.firstOrNull { it.date == sel }?.let {
            MemoRepository.parseEntries(it.content, it.date)
        } ?: emptyList()

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

        CalendarUiState(
            selected = sel,
            daySummary = DaySummary(events = dayEvents, memos = dayMemos),
            markedDates = markers,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, CalendarUiState())

    fun selectDate(date: LocalDate) { _selected.value = date }

    fun createEvent(summary: String, startMs: Long, endMs: Long, rrule: String?, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            eventRepo.create(summary, startMs, endMs, rrule = rrule)
            onDone()
        }
    }

    fun updateEvent(uid: String, summary: String, startMs: Long, endMs: Long, rrule: String?, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            eventRepo.update(uid, summary, startMs, endMs, rrule = rrule)
            onDone()
        }
    }

    fun deleteEvent(uid: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            eventRepo.delete(uid)
            onDone()
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
