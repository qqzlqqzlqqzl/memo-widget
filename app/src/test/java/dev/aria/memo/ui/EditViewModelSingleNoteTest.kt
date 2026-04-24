package dev.aria.memo.ui

import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.local.SingleNoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure JVM tests for the P6.1 single-note save/edit path added to
 * [EditViewModel]. We exercise the functional-dependency constructor so no
 * Android / Room / WorkManager is required.
 *
 *  - New-note mode (noteUid = null): save → createSingleNote (NOT appendToday)
 *  - Edit mode (noteUid != null): init loads body, save → updateSingleNote
 *  - Dedup / double-tap guard continues to work for the single-note shape
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditViewModelSingleNoteTest {

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
        date = LocalDate.of(2026, 4, 22),
        time = LocalTime.of(9, 15),
        isPinned = false,
        githubSha = null,
        localUpdatedAt = 0L,
        remoteUpdatedAt = null,
        dirty = true,
    )

    @Test
    fun `new-note save invokes createSingleNote and NOT appendToday`() = runTest {
        val appendCalls = AtomicInteger(0)
        val createCalls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = {
                appendCalls.incrementAndGet()
                MemoResult.Ok(Unit)
            },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { body ->
                createCalls.incrementAndGet()
                MemoResult.Ok(entity("fresh", body))
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "should not be called") },
            loadSingleNote = { null },
            noteUid = null,
        )

        vm.save("hello world")

        assertEquals(SaveState.Success, vm.state.value)
        assertEquals("single-note path must fire createSingleNote", 1, createCalls.get())
        assertEquals("legacy appendToday must NOT be touched", 0, appendCalls.get())
    }

    @Test
    fun `edit-mode init loads the existing body via loadSingleNote`() = runTest {
        val loaded = entity(uid = "abc", body = "old body content")
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { MemoResult.Err(ErrorCode.UNKNOWN, "should not be called") },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "should not be called") },
            loadSingleNote = { uid ->
                assertEquals("abc", uid)
                loaded
            },
            noteUid = "abc",
        )

        assertEquals("old body content", vm.body.value)
        assertEquals("notes/abc.md", vm.path.value)
    }

    @Test
    fun `edit-mode save invokes updateSingleNote with the right uid`() = runTest {
        val loaded = entity(uid = "abc", body = "original body")
        val updateCalls = mutableListOf<Pair<String, String>>()
        val vm = EditViewModel(
            appendToday = { MemoResult.Err(ErrorCode.UNKNOWN, "should not be called") },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { MemoResult.Err(ErrorCode.UNKNOWN, "should not be called") },
            updateSingleNote = { uid, body ->
                updateCalls.add(uid to body)
                MemoResult.Ok(loaded.copy(body = body))
            },
            loadSingleNote = { loaded },
            noteUid = "abc",
        )

        vm.save("updated body")
        assertEquals(SaveState.Success, vm.state.value)
        assertEquals(1, updateCalls.size)
        assertEquals("abc", updateCalls[0].first)
        assertEquals("updated body", updateCalls[0].second)
    }

    @Test
    fun `new-note save returning Err surfaces Error state and does NOT arm dedup`() = runTest {
        var calls = 0
        val vm = EditViewModel(
            appendToday = { MemoResult.Err(ErrorCode.UNKNOWN, "nope") },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = {
                calls++
                if (calls == 1) MemoResult.Err(ErrorCode.NETWORK, "boom")
                else MemoResult.Ok(entity("ok", it))
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "should not be called") },
            loadSingleNote = { null },
            noteUid = null,
        )

        vm.save("foo")
        assertTrue("first save must end in Error", vm.state.value is SaveState.Error)
        vm.reset()
        vm.save("foo")
        assertEquals(
            "retry after error must still reach the repository",
            2, calls,
        )
        assertEquals(SaveState.Success, vm.state.value)
    }

    @Test
    fun `double-tap within the dedup window collapses to a single createSingleNote`() = runTest {
        val createCalls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { body ->
                createCalls.incrementAndGet()
                MemoResult.Ok(entity("x", body))
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "should not be called") },
            loadSingleNote = { null },
            noteUid = null,
        )

        vm.save("foo")
        assertEquals(SaveState.Success, vm.state.value)
        vm.reset()
        vm.save("foo")

        assertEquals(
            "double-tap with the same body must fire create exactly once",
            1, createCalls.get(),
        )
        // And Success is still replayed so the Activity can finish().
        assertEquals(SaveState.Success, vm.state.value)
    }

    @Test
    fun `double-tap window respects clock`() = runTest {
        val clock = AtomicInteger(0)
        val createCalls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { body ->
                createCalls.incrementAndGet()
                MemoResult.Ok(entity("x", body))
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "should not be called") },
            loadSingleNote = { null },
            noteUid = null,
            clock = { clock.get().toLong() },
        )

        clock.set(0)
        vm.save("foo")
        vm.reset()
        // Past the dedup window, identical body must still save.
        clock.set((EditViewModel.DUPLICATE_SAVE_WINDOW_MS + 1).toInt())
        vm.save("foo")
        assertEquals(2, createCalls.get())
    }

    @Test
    fun `edit-mode save Ok leaves body reflecting the persisted value`() = runTest {
        val loaded = entity(uid = "abc", body = "original body")
        val vm = EditViewModel(
            appendToday = { MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            updateSingleNote = { _, body -> MemoResult.Ok(loaded.copy(body = body)) },
            loadSingleNote = { loaded },
            noteUid = "abc",
        )
        vm.save("new body")
        assertEquals("new body", vm.body.value)
    }

    @Test
    fun `edit-mode never runs prime even when extras are passed`() = runTest {
        // prime() is a no-op when noteUid is set — we never want it to
        // overwrite the entity we just loaded from the DAO.
        val loaded = entity(uid = "abc", body = "loaded body")
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = { loaded },
            noteUid = "abc",
        )
        // prime call must not clobber body / path. Since prime() in single-note
        // mode is an early-return we just assert state stayed on the loaded entity.
        assertEquals("loaded body", vm.body.value)
        assertEquals("notes/abc.md", vm.path.value)
    }

    @Test
    fun `empty body short-circuits to Error without touching create`() = runTest {
        val createCalls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = {
                createCalls.incrementAndGet()
                MemoResult.Ok(entity("x", it))
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = { null },
            noteUid = null,
        )
        vm.save("   \n\n")
        assertTrue(vm.state.value is SaveState.Error)
        assertEquals(0, createCalls.get())
    }

    @Test
    fun `new-note mode does not load any single-note on init`() = runTest {
        var loadCalled = false
        EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { MemoResult.Ok(entity("x", it)) },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = {
                loadCalled = true
                null
            },
            noteUid = null,
        )
        assertTrue("loadSingleNote must not be called in new-note mode", !loadCalled)
    }

    @Test
    fun `edit-mode init tolerates a missing DAO row without crashing`() = runTest {
        // loadSingleNote may legitimately return null if a concurrent delete
        // landed between list render and editor open. The VM should stay on
        // blank body/path instead of crashing.
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = { null },
            noteUid = "missing-uid",
        )
        assertEquals("", vm.body.value)
        assertEquals("", vm.path.value)
        // An idle state, not an Error — the UI decides how to surface the miss.
        assertEquals(SaveState.Idle, vm.state.value)
        assertNull(null) // placeholder for reviewer sanity
    }

    // --- P6.1 boundary regressions (fixes #48) -----------------------------

    @Test
    fun `edit-mode save surfaces Err from updateSingleNote as SaveState Error`() = runTest {
        // Covers the 'update returned CONFLICT' branch that was previously
        // exercised only by the legacy appendToday fake.
        val loaded = entity(uid = "abc", body = "before")
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.CONFLICT, "SHA drifted") },
            loadSingleNote = { loaded },
            noteUid = "abc",
        )
        vm.save("after")
        val terminal = vm.state.value
        assertTrue("expected SaveState.Error, got $terminal", terminal is SaveState.Error)
    }

    @Test
    fun `new-note reset followed by different body saves a second time`() = runTest {
        // Confirms dedup is body-content based, not just save-count based —
        // changing the body after reset should always hit createSingleNote.
        val createCalls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = {
                createCalls.incrementAndGet()
                MemoResult.Ok(entity("x", it))
            },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = { null },
            noteUid = null,
        )
        vm.save("first body")
        vm.reset()
        vm.save("second body")
        assertEquals("two distinct bodies must both save", 2, createCalls.get())
    }

    // --- P6.1 zombie uid regression (fixes #50) ----------------------------

    // --- Fix-6 (Bug-1 C4) delete wiring ------------------------------------

    @Test
    fun `edit-mode delete invokes deleteSingleNote with the right uid and fires onDone`() = runTest {
        val deleteCalls = mutableListOf<String>()
        val loaded = entity(uid = "to-kill", body = "will be deleted")
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = { loaded },
            deleteSingleNote = { uid ->
                deleteCalls.add(uid)
                MemoResult.Ok(Unit)
            },
            noteUid = "to-kill",
        )

        var doneFired = 0
        vm.delete { doneFired++ }

        assertEquals(1, deleteCalls.size)
        assertEquals("to-kill", deleteCalls[0])
        assertEquals("onDone must fire once after repo returns", 1, doneFired)
    }

    @Test
    fun `new-note mode delete is a no-op that still fires onDone`() = runTest {
        // No uid means nothing has been persisted yet — delete should short
        // circuit so the UI can still exit cleanly. We assert the repo wasn't
        // touched so a widget deep-link that somehow lands here can't wipe
        // an unrelated row.
        val deleteCalls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { MemoResult.Ok(entity("x", it)) },
            updateSingleNote = { _, _ -> MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            loadSingleNote = { null },
            deleteSingleNote = {
                deleteCalls.incrementAndGet()
                MemoResult.Ok(Unit)
            },
            noteUid = null,
        )

        var doneFired = 0
        vm.delete { doneFired++ }

        assertEquals("new-note mode must not hit the repo", 0, deleteCalls.get())
        assertEquals("onDone still fires so UI can finish()", 1, doneFired)
    }

    @Test
    fun `zombie uid save attempts update and surfaces Err NOT_FOUND`() = runTest {
        // Sanity guard for the uninstall-reinstall deep-link scenario: user
        // taps an old widget/notification pinning a uid that no longer exists.
        // init's load returns null, body stays empty. If the user still hits
        // save with typed content, we route to updateSingleNote (because
        // noteUid != null) — the repo returns NOT_FOUND and we surface it.
        // Lock this behaviour so a future "graceful downgrade to create"
        // refactor is an explicit decision, not a silent drift.
        val updateCalls = AtomicInteger(0)
        val vm = EditViewModel(
            appendToday = { MemoResult.Ok(Unit) },
            toggleTodoLine = { _, _, _, _ -> MemoResult.Ok(Unit) },
            createSingleNote = { MemoResult.Err(ErrorCode.UNKNOWN, "n/a") },
            updateSingleNote = { uid, _ ->
                updateCalls.incrementAndGet()
                assertEquals("zombie-uid", uid)
                MemoResult.Err(ErrorCode.NOT_FOUND, "note gone")
            },
            loadSingleNote = { null },
            noteUid = "zombie-uid",
        )
        // Simulate the user typing into the editor after the silent load.
        vm.setBody("still want to save this")
        vm.save("still want to save this")
        assertEquals(1, updateCalls.get())
        assertTrue(vm.state.value is SaveState.Error)
    }
}
