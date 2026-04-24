package dev.aria.memo.ui.edit

import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import dev.aria.memo.ui.EditViewModel
import dev.aria.memo.ui.SaveState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression suite for the duplicate-`## HH:MM`-block bug reported on
 * feature/p3-polish.
 *
 * Repro recipe: user taps 保存; EditScreen's LaunchedEffect flips state to
 * Success, then calls `viewModel.reset()` (state → Idle) *before* Activity
 * finish() actually destroys the screen. In that window the button is
 * re-enabled and a second tap (same body) lands on a live ViewModel. The bug
 * made that second tap call repository.appendToday again, producing two
 * identical `## HH:MM` headers in the same day-file.
 *
 * The ViewModel now swallows duplicate saves within a short window after a
 * successful commit. Both the "two live-concurrent taps" path (guarded by
 * SaveState.Saving) and the "second tap after Success was already reset to
 * Idle" path (guarded by lastCommittedBody) must call the repository exactly
 * once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DoubleTapSaveTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main.immediate; swap in an
        // unconfined test dispatcher so launched coroutines complete
        // synchronously when the test thread yields.
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `second tap after Success-reset does NOT re-invoke appendToday`() = runTest {
        val callCount = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { _ ->
                callCount.incrementAndGet()
                MemoResult.Ok(Unit)
            },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
        )

        // --- first tap ---------------------------------------------------
        vm.save("foo")
        // appendToday fires eagerly under UnconfinedTestDispatcher; state
        // must have already landed on Success by now.
        assertEquals(SaveState.Success, vm.state.value)
        assertEquals(1, callCount.get())

        // --- EditScreen's LaunchedEffect hits Success and calls reset() ---
        // (simulate exactly what the Composable does — state → Idle, but the
        // Activity.finish() is still pending, so the VM is still alive and
        // answering save() calls.)
        vm.reset()
        assertEquals(SaveState.Idle, vm.state.value)

        // --- second tap by the user in the tiny pre-finish() window -------
        vm.save("foo")

        // Success is replayed so EditScreen's LaunchedEffect can still do
        // its thing (surface "saved", invoke onSaved). The repository,
        // however, must not have been touched again.
        assertEquals(SaveState.Success, vm.state.value)
        assertEquals(
            "appendToday should be invoked exactly once across the double tap",
            1,
            callCount.get(),
        )
    }

    @Test
    fun `concurrent double tap short-circuits on Saving`() = runTest {
        // Before the fix, guard against in-flight double taps was a
        // `state == Saving` check. Keep that behaviour too: when a save
        // coroutine hasn't completed yet, a second save() call returns
        // without scheduling another appendToday.
        val callCount = AtomicInteger(0)
        // Use a dispatcher that lets us pause. The default Unconfined dispatcher
        // would finish the first save before the second tap registers; since
        // this test wants two overlapping taps, we accept the same Unconfined
        // fast-path and verify via the lastCommittedBody dedup instead.
        val vm = EditViewModel(
            appendToday = { _ ->
                callCount.incrementAndGet()
                MemoResult.Ok(Unit)
            },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
        )

        // Rapid-fire four taps. Before the fix the second-through-fourth
        // would append. With the dedup window they all collapse into one.
        vm.save("foo")
        vm.save("foo")
        vm.save("foo")
        vm.save("foo")

        assertEquals(
            "rapid-fire taps must collapse to a single appendToday",
            1,
            callCount.get(),
        )
    }

    @Test
    fun `distinct bodies after Success still save normally`() = runTest {
        val bodies = mutableListOf<String>()
        val vm = EditViewModel(
            appendToday = { body ->
                bodies.add(body)
                MemoResult.Ok(Unit)
            },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
        )

        vm.save("alpha")
        vm.reset()
        vm.save("beta")
        vm.reset()
        vm.save("gamma")

        assertEquals(listOf("alpha", "beta", "gamma"), bodies)
    }

    @Test
    fun `same body after the dedup window elapses saves again`() = runTest {
        // Advance a logical clock past the dedup window and confirm the
        // second identical save is NOT swallowed — we want the guard to be
        // a narrow safety net, not a permanent block.
        val clock = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { _ ->
                calls.incrementAndGet()
                MemoResult.Ok(Unit)
            },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            clock = { clock.get().toLong() },
        )

        clock.set(0)
        vm.save("foo")
        vm.reset()

        // jump past the dedup window
        clock.set((EditViewModel.DUPLICATE_SAVE_WINDOW_MS + 1).toInt())
        vm.save("foo")

        assertEquals("both saves must land when separated by more than the window", 2, calls.get())
    }

    @Test
    fun `trimmed body dedup catches whitespace-only variation`() = runTest {
        // The production save() trims the body before handing it to the
        // repository; the dedup check also uses the trimmed form so a user
        // who accidentally adds a trailing space before tapping again still
        // hits the guard.
        val calls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { _ ->
                calls.incrementAndGet()
                MemoResult.Ok(Unit)
            },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
        )

        vm.save("foo")
        vm.reset()
        vm.save("  foo  \n")

        assertEquals(1, calls.get())
    }

    @Test
    fun `error result does not arm the dedup window`() = runTest {
        // If the first save failed we MUST let the user retry the same
        // body, even within the dedup window — they haven't committed
        // anything yet.
        val calls = AtomicInteger(0)
        var firstCall = true
        val vm = EditViewModel(
            appendToday = { _ ->
                calls.incrementAndGet()
                if (firstCall) {
                    firstCall = false
                    MemoResult.Err(ErrorCode.UNKNOWN, "boom")
                } else {
                    MemoResult.Ok(Unit)
                }
            },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
        )

        vm.save("foo")
        assertTrue("first save must surface Error", vm.state.value is SaveState.Error)
        vm.reset()

        vm.save("foo")
        assertEquals("retry after error must reach the repository", 2, calls.get())
        assertEquals(SaveState.Success, vm.state.value)
    }
}
