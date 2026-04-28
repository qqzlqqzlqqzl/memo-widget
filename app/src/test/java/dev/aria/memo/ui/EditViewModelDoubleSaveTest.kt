package dev.aria.memo.ui

import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.local.SingleNoteEntity
import dev.aria.memo.ui.edit.EditViewModel
import dev.aria.memo.ui.edit.SaveState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fix-EV1 (Bug-1 M8) regression tests for the AtomicBoolean re-entrancy guard
 * on [EditViewModel.save].
 *
 * Background: the older guard was `if (_state.value is SaveState.Saving) return`,
 * a non-atomic read-then-write. Two threads (or a paused dispatcher with
 * queued continuations) could both observe Idle before either flipped state to
 * Saving, and both would launch a viewModelScope coroutine. That re-entered
 * the repository, double-pushed to GitHub, and corrupted the dirty flag.
 *
 * The fix uses [java.util.concurrent.atomic.AtomicBoolean.compareAndSet] —
 * a single atomic instruction — so concurrent callers see exactly one
 * winner, and N-1 immediate returns. This mirrors the P6.1 mutating guard
 * used in [dev.aria.memo.ui.calendar.CalendarViewModel] for the same race
 * class on event create / update / delete.
 *
 * The acceptance scenario the task spec calls out: **10 consecutive taps
 * trigger exactly one actual save**.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditViewModelDoubleSaveTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entity(uid: String, body: String) = SingleNoteEntity(
        uid = uid,
        filePath = "notes/$uid.md",
        title = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
        body = body,
        date = LocalDate.of(2026, 4, 24),
        time = LocalTime.of(12, 0),
        isPinned = false,
        githubSha = null,
        localUpdatedAt = 0L,
        remoteUpdatedAt = null,
        dirty = true,
    )

    /**
     * Acceptance test: 10 frantic taps must collapse to 1 actual save call.
     *
     * We use a dispatcher we control so the launched coroutine stays
     * suspended until we explicitly drain it — that way every one of the 10
     * taps lands while the first save is still "in flight". Without the
     * AtomicBoolean guard, taps 2..10 would each schedule a fresh coroutine
     * and the repo would be hit 10 times. With the guard, taps 2..10 hit
     * `compareAndSet(false, true)` and bail immediately.
     */
    @Test
    fun `10 frantic taps collapse to exactly 1 createSingleNote call`() = runTest {
        // Pull viewModelScope onto a dispatcher we drive manually so we can
        // freeze the in-flight save mid-coroutine while issuing more taps.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        val createCalls = AtomicInteger(0)
        // A latch that the first (and only legitimate) repository call will
        // await on. As long as it's unresolved, the in-flight save is parked
        // and `saving` stays true — every subsequent tap should be rejected.
        val gate = CompletableDeferred<Unit>()
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { body ->
                createCalls.incrementAndGet()
                gate.await()
                MemoResult.Ok(entity("only-one", body))
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "should not be called") },
            loadSingleNote = { null },
            noteUid = null,
        )

        // 10x button mash. Only the first should pass the AtomicBoolean check.
        repeat(10) { vm.save("frantic body") }

        // Let the first launched coroutine actually start executing
        // createSingleNote (which immediately suspends on `gate`).
        advanceUntilIdle()

        // Tap count one of the bug invariants: even with the gate still
        // closed, the call counter should already be at 1 — the first tap
        // got through, the other 9 returned at the AtomicBoolean check
        // before they ever reached the launch block.
        assertEquals(
            "10 taps must produce exactly one createSingleNote invocation",
            1,
            createCalls.get(),
        )

        // Release the gate; the in-flight save resolves and returns to Idle.
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(SaveState.Success, vm.state.value)
        assertEquals(
            "post-resolution sanity: still exactly one save",
            1,
            createCalls.get(),
        )
    }

    /**
     * Same as the 10-tap case, but exercises the edit-mode update path so we
     * know the AtomicBoolean guard wraps both branches of the routing if/else.
     */
    @Test
    fun `10 frantic taps on edit-mode collapse to 1 updateSingleNote`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        val updateCalls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val loaded = entity("abc", "loaded")
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { MemoResult.Err(ErrorCode.UNKNOWN, "should not be called") },
            updateSingleNote = { _, body ->
                updateCalls.incrementAndGet()
                gate.await()
                MemoResult.Ok(loaded.copy(body = body))
            },
            loadSingleNote = { loaded },
            noteUid = "abc",
        )
        // Drain the init load so it doesn't tangle with the test below.
        advanceUntilIdle()

        repeat(10) { vm.save("new body") }
        advanceUntilIdle()

        assertEquals(1, updateCalls.get())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SaveState.Success, vm.state.value)
        assertEquals(1, updateCalls.get())
    }

    /**
     * Empty-body taps must still release the AtomicBoolean. Otherwise a user
     * who accidentally fired a save with empty content could permanently
     * lock save() out for the rest of the ViewModel's life.
     */
    @Test
    fun `empty-body short-circuit still releases the saving guard`() = runTest {
        val createCalls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { body ->
                createCalls.incrementAndGet()
                MemoResult.Ok(entity("x", body))
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = { null },
            noteUid = null,
        )

        // Empty body trips the validation and surfaces Error...
        vm.save("   \n")
        assertTrue(vm.state.value is SaveState.Error)
        assertEquals(0, createCalls.get())

        // ...but the very next legitimate save MUST succeed; the guard
        // can't be left in the "true" state by the early return.
        vm.reset()
        vm.save("real body")
        assertEquals(SaveState.Success, vm.state.value)
        assertEquals(1, createCalls.get())
    }

    /**
     * The dedup-window replay path is also a guarded early return — make sure
     * it releases the AtomicBoolean too, otherwise the next non-duplicate
     * save would silently no-op.
     */
    @Test
    fun `dedup-window replay still releases the saving guard`() = runTest {
        val createCalls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { body ->
                createCalls.incrementAndGet()
                MemoResult.Ok(entity("x", body))
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = { null },
            noteUid = null,
        )

        vm.save("foo")
        assertEquals(SaveState.Success, vm.state.value)
        assertEquals(1, createCalls.get())

        // Same body within the dedup window → replays Success without
        // touching the repo, but must release the guard.
        vm.reset()
        vm.save("foo")
        assertEquals(SaveState.Success, vm.state.value)
        assertEquals("dedup replay must NOT count as a real save", 1, createCalls.get())

        // A different body now must be able to land.
        vm.reset()
        vm.save("bar")
        assertEquals(SaveState.Success, vm.state.value)
        assertEquals("post-dedup distinct body must reach the repo", 2, createCalls.get())
    }

    /**
     * Repository failure path must release the guard via the `finally` block
     * so the user can retry. If `finally` regressed, every retry would
     * silently be swallowed.
     */
    @Test
    fun `repo failure releases the saving guard so retry works`() = runTest {
        var firstCall = true
        val calls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { body ->
                calls.incrementAndGet()
                if (firstCall) {
                    firstCall = false
                    MemoResult.Err(ErrorCode.NETWORK, "boom")
                } else {
                    MemoResult.Ok(entity("ok", body))
                }
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = { null },
            noteUid = null,
        )

        vm.save("foo")
        assertTrue("first save must end in Error", vm.state.value is SaveState.Error)
        assertEquals(1, calls.get())

        // The guard MUST be released so the user can retry.
        vm.reset()
        vm.save("foo")
        assertEquals(SaveState.Success, vm.state.value)
        assertEquals("retry after repo failure must reach the repo again", 2, calls.get())
    }

    /**
     * After a successful save completes (state lands on Success / Idle), the
     * guard should be free again for the next *distinct* body.
     */
    @Test
    fun `successful save releases the saving guard for distinct bodies`() = runTest {
        val calls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { body ->
                calls.incrementAndGet()
                MemoResult.Ok(entity("x", body))
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = { null },
            noteUid = null,
        )

        vm.save("alpha")
        vm.reset()
        vm.save("beta")
        vm.reset()
        vm.save("gamma")
        assertEquals(3, calls.get())
    }

    /**
     * Stress: parallel coroutines all calling save() at once. Even with
     * dispatcher concurrency the AtomicBoolean must serialize exactly one
     * winner.
     */
    @Test
    fun `parallel save invocations collapse to a single repo call`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { body ->
                calls.incrementAndGet()
                gate.await()
                MemoResult.Ok(entity("x", body))
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = { null },
            noteUid = null,
        )

        // Fire 20 parallel save() invocations. compareAndSet must let
        // exactly one through; the other 19 see `true` and bail.
        val tasks = (1..20).map { async { vm.save("parallel body") } }
        tasks.awaitAll()
        advanceUntilIdle()

        assertEquals("only one save coroutine survives the AtomicBoolean", 1, calls.get())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, calls.get())
        assertEquals(SaveState.Success, vm.state.value)
    }
}
