package dev.aria.memo.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * JVM unit tests for [CrashLogger]'s public surface.
 *
 * Doesn't go through [CrashLogger.install] in most tests because installing
 * mutates `Thread.defaultUncaughtExceptionHandler` globally; one test exercises
 * it explicitly to lock down the pruning contract, with @After restoring the
 * previous handler so other tests in the same JVM aren't poisoned.
 */
@RunWith(RobolectricTestRunner::class)
// Skip MemoApplication.onCreate (Keystore + WorkManager init) — these tests
// only need a vanilla Context with filesDir.
@Config(sdk = [33], application = android.app.Application::class)
class CrashLoggerTest {

    private lateinit var ctx: Context
    private lateinit var crashDir: File
    private var savedHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        savedHandler = Thread.getDefaultUncaughtExceptionHandler()
        crashDir = CrashLogger.crashDir(ctx)
        // Each test starts from an empty crash dir.
        crashDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun teardown() {
        Thread.setDefaultUncaughtExceptionHandler(savedHandler)
        crashDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `crashDir auto-creates the directory`() {
        // Even after a wipe, the next call should re-create it.
        crashDir.deleteRecursively()
        val recreated = CrashLogger.crashDir(ctx)
        assertTrue(recreated.isDirectory)
    }

    @Test
    fun `summary is empty when no crash files`() {
        val s = CrashLogger.summary(ctx)
        assertEquals(0, s.count)
        assertEquals(null, s.latestEpochMs)
    }

    @Test
    fun `summary reports count and latest mtime`() {
        val older = File(crashDir, "crash-older.txt").apply {
            writeText("old")
            setLastModified(1000L)
        }
        val newer = File(crashDir, "crash-newer.txt").apply {
            writeText("new")
            setLastModified(2000L)
        }
        val s = CrashLogger.summary(ctx)
        assertEquals(2, s.count)
        // Robolectric setLastModified can be coarse; sanity check it's
        // the newer file's value (or at least >= it).
        assertNotNull(s.latestEpochMs)
        assertTrue(s.latestEpochMs!! >= 2000L)
        assertTrue(older.exists())
        assertTrue(newer.exists())
    }

    @Test
    fun `clearAll removes everything and reports count`() {
        repeat(3) { i ->
            File(crashDir, "crash-$i.txt").writeText("x")
        }
        assertEquals(3, CrashLogger.summary(ctx).count)

        val deleted = CrashLogger.clearAll(ctx)

        assertEquals(3, deleted)
        assertEquals(0, CrashLogger.summary(ctx).count)
    }

    @Test
    fun `install prunes when crash dir already has more than MAX_KEEP files`() {
        // Seed with 8 files (> MAX_KEEP=5) at staggered mtimes so prune has
        // a deterministic newest-5 set to keep.
        repeat(8) { i ->
            File(crashDir, "crash-pre-$i.txt").apply {
                writeText("seed")
                setLastModified(1000L + i)
            }
        }
        assertEquals(8, CrashLogger.summary(ctx).count)

        CrashLogger.install(ctx)

        assertEquals(5, CrashLogger.summary(ctx).count)
        // The 5 newest mtimes (1003..1007) survive; 1000..1002 pruned.
        val survivors = crashDir.listFiles()!!.map { it.lastModified() }.sorted()
        assertEquals(listOf(1003L, 1004L, 1005L, 1006L, 1007L), survivors)
    }

    @Test
    fun `install does not prune when count is at or below MAX_KEEP`() {
        repeat(3) { i -> File(crashDir, "crash-$i.txt").writeText("x") }
        CrashLogger.install(ctx)
        assertEquals(3, CrashLogger.summary(ctx).count)
    }

    /**
     * End-to-end: install the handler, simulate the runtime invoking it on
     * an uncaught exception (we don't actually crash the test JVM — we call
     * the handler manually), and verify a crash file lands in [crashDir]
     * with the throwable's message + stack inlined.
     *
     * Locking down this path matters most because the original
     * UncaughtExceptionHandler interaction is the entire feature; a regression
     * here would silently break "next-launch crash review" with no other
     * surface area lighting up.
     */
    @Test
    fun `install handler writes crash file containing thrown exception`() {
        // Stub the previous handler so we can verify chaining without the
        // real Robolectric default doing anything visible.
        var chained = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained = true }

        CrashLogger.install(ctx)
        val handler = Thread.getDefaultUncaughtExceptionHandler()
            ?: error("install() did not register a handler")

        val sentinel = "BOOM_${System.nanoTime()}"
        val throwable = RuntimeException(sentinel)

        handler.uncaughtException(Thread.currentThread(), throwable)

        // File should now exist with the message inlined.
        val files = crashDir.listFiles().orEmpty()
        assertEquals("expected exactly one crash file", 1, files.size)
        val body = files[0].readText()
        assertTrue("body contains exception message", body.contains(sentinel))
        assertTrue("body contains class name", body.contains("RuntimeException"))
        assertTrue("body contains 'Thread:' header", body.contains("Thread: "))
        assertTrue("chained to previous handler", chained)
    }
}
