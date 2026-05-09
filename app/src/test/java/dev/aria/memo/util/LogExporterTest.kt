package dev.aria.memo.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * JVM unit tests for [LogExporter.captureToFile]. The `logcat` binary doesn't
 * exist under Robolectric so the dump section ends with the
 * "logcat 调用失败" diagnostic line — that's intentional and expected; we still
 * verify the surrounding scaffolding (header, crash-section append) which is
 * the part most likely to regress when refactoring.
 *
 * `shareIntent` is a thin FileProvider wrapper; integration testing it would
 * require the FileProvider authority to resolve under Robolectric's manifest
 * merger, so we leave it for instrumented tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LogExporterTest {

    private lateinit var ctx: Context
    private lateinit var logsDir: File
    private lateinit var crashDir: File

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        logsDir = File(ctx.cacheDir, "logs")
        crashDir = CrashLogger.crashDir(ctx)
        logsDir.listFiles()?.forEach { it.delete() }
        crashDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun teardown() {
        logsDir.listFiles()?.forEach { it.delete() }
        crashDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `captureToFile creates a file in cacheDir-logs`() {
        val out = LogExporter.captureToFile(ctx)
        assertTrue("file exists", out.exists())
        assertEquals("logs", out.parentFile!!.name)
        assertEquals(ctx.cacheDir, out.parentFile!!.parentFile)
        assertTrue("filename starts with prefix", out.name.startsWith("memo-log-"))
        assertTrue("filename ends with .txt", out.name.endsWith(".txt"))
    }

    @Test
    fun `captureToFile writes the standard header`() {
        val out = LogExporter.captureToFile(ctx)
        val text = out.readText()
        assertTrue("has top banner", text.contains("=== Memo Widget log export ==="))
        assertTrue("has App line", text.contains("App: "))
        assertTrue("has Device line", text.contains("Device: "))
        assertTrue("has PID line", text.contains("PID: "))
        assertTrue("has Captured line", text.contains("Captured: "))
        assertTrue("has logcat header", text.contains("=== logcat -d"))
    }

    @Test
    fun `captureToFile reports no persisted crashes when crash dir empty`() {
        val out = LogExporter.captureToFile(ctx)
        val text = out.readText()
        assertTrue("crash section exists", text.contains("=== Persisted crashes"))
        assertTrue("declares none", text.contains("(none)"))
    }

    @Test
    fun `captureToFile inlines persisted crash file contents`() {
        File(crashDir, "crash-A.txt").writeText("STACKTRACE_A_LINE")
        File(crashDir, "crash-B.txt").writeText("STACKTRACE_B_LINE")

        val out = LogExporter.captureToFile(ctx)
        val text = out.readText()

        assertTrue("section header present", text.contains("=== Persisted crashes"))
        assertTrue("crash-A name shown", text.contains("--- crash-A.txt ---"))
        assertTrue("crash-A body inlined", text.contains("STACKTRACE_A_LINE"))
        assertTrue("crash-B name shown", text.contains("--- crash-B.txt ---"))
        assertTrue("crash-B body inlined", text.contains("STACKTRACE_B_LINE"))
        // (none) may legitimately appear in the ApplicationExitInfo section when
        // the OS hasn't recorded any historical exits, so don't assert its
        // absence globally — instead pin the crashes section specifically.
        val crashesSection = text.substringAfter("=== Persisted crashes")
        assertTrue("crashes section does not declare none", !crashesSection.contains("(none)"))
    }

    @Test
    fun `successive captureToFile calls produce distinct files`() {
        val a = LogExporter.captureToFile(ctx)
        // Force ms granularity to advance — SimpleDateFormat is "yyyyMMdd-HHmmss"
        // (1s resolution), so a tight loop can collide. Sleep just past 1s.
        Thread.sleep(1100)
        val b = LogExporter.captureToFile(ctx)
        assertNotEquals(a.name, b.name)
        assertTrue(a.exists())
        assertTrue(b.exists())
    }
}
