package dev.aria.memo.util

import android.content.Context
import dev.aria.memo.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent crash logger. Chains in front of the default
 * UncaughtExceptionHandler so the stack trace lands on disk before the
 * process dies. Logcat's circular buffer can lose crashes by the time the
 * user exports logs the next morning; these files survive in filesDir.
 *
 * [LogExporter] tails this directory at export time so the dev gets both
 * live logcat + any historical crashes in one .txt.
 *
 * Capped at [MAX_KEEP] files to prevent unbounded growth in a crash loop.
 */
object CrashLogger {

    private const val DIR = "crash"
    private const val MAX_KEEP = 5

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val dir = crashDir(app)
        pruneOld(dir)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(dir, thread, throwable)
            } catch (_: Throwable) {
                // Swallow — chaining to the previous handler is the priority,
                // because that's what tells the OS to kill the process and
                // show "memo-widget keeps stopping" to the user.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun crashDir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }

    /**
     * Snapshot of persisted crash files, newest first.
     * Empty list if none.
     */
    data class CrashSummary(val count: Int, val latestEpochMs: Long?)

    fun summary(ctx: Context): CrashSummary {
        val files = crashDir(ctx).listFiles()?.toList() ?: emptyList()
        val latest = files.maxByOrNull { it.lastModified() }?.lastModified()
        return CrashSummary(count = files.size, latestEpochMs = latest)
    }

    /**
     * Wipes all persisted crash files. Used by the "导出 + 清空" / "仅清空"
     * actions in Settings so the indicator card can dismiss after triage.
     * Returns the number of files deleted.
     */
    fun clearAll(ctx: Context): Int {
        val files = crashDir(ctx).listFiles() ?: return 0
        var deleted = 0
        files.forEach { if (it.delete()) deleted++ }
        return deleted
    }

    private fun writeCrash(dir: File, thread: Thread, throwable: Throwable) {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "crash-$ts.txt")
        out.bufferedWriter().use { w ->
            w.append("App: ${BuildConfig.APPLICATION_ID} v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n")
            w.append("Time: ${Date()}\n")
            w.append("Thread: ${thread.name} (id=${thread.id})\n\n")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            w.append(sw.toString())
        }
    }

    private fun pruneOld(dir: File) {
        val files = dir.listFiles()?.toList() ?: return
        if (files.size <= MAX_KEEP) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_KEEP)
            .forEach { runCatching { it.delete() } }
    }
}
