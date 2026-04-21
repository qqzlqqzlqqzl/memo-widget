package dev.aria.memo.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.aria.memo.data.EventRepository
import dev.aria.memo.data.MemoEntry
import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.local.EventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Aggregate of events + memos on a single day, ready for the day-sheet UI. */
data class DaySummary(
    val events: List<EventEntity>,
    val memos: List<MemoEntry>,
)

data class CalendarUiState(
    val selected: LocalDate = LocalDate.now(),
    val daySummary: DaySummary = DaySummary(emptyList(), emptyList()),
    /** Dates in the currently visible range that have any events or memos — for dots on the grid. */
    val markedDates: Set<LocalDate> = emptySet(),
)

class CalendarViewModel(
    private val memoRepo: MemoRepository,
    private val eventRepo: EventRepository,
) : ViewModel() {

    private val _selected = MutableStateFlow(LocalDate.now())

    private val allEvents = eventRepo.observeAll()
    private val allNotes = memoRepo.observeNotes()

    val state: StateFlow<CalendarUiState> = combine(allEvents, allNotes, _selected) { events, notes, sel ->
        val zoneId = ZoneId.systemDefault()

        // Day-summary for selected date.
        val dayStart = sel.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = sel.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEvents = events.filter { it.startEpochMs < dayEnd && it.endEpochMs >= dayStart }
        val dayMemos = notes.firstOrNull { it.date == sel }?.let {
            MemoRepository.parseEntries(it.content, it.date)
        } ?: emptyList()

        // Markers across the full data set (cheap — Room returns everything that's not tombstoned + dates we have files for).
        val markers = HashSet<LocalDate>()
        for (ev in events) {
            // Mark every day the event spans.
            var d = java.time.Instant.ofEpochMilli(ev.startEpochMs).atZone(zoneId).toLocalDate()
            val endDate = java.time.Instant.ofEpochMilli(ev.endEpochMs).atZone(zoneId).toLocalDate()
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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CalendarUiState())

    fun selectDate(date: LocalDate) { _selected.value = date }

    fun createEvent(summary: String, startMs: Long, endMs: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            eventRepo.create(summary, startMs, endMs)
            onDone()
        }
    }

    fun updateEvent(uid: String, summary: String, startMs: Long, endMs: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            eventRepo.update(uid, summary, startMs, endMs)
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
