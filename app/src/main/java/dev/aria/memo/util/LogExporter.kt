package dev.aria.memo.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import dev.aria.memo.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures the current process's recent logcat output to a file in cacheDir,
 * then exposes it via FileProvider so the user can share it with the dev for
 * triage. On Android 4.1+, regular apps can only read their own process's
 * logs anyway — `logcat -d` returns just our own lines.
 *
 * The file is plaintext and includes a header with app version + device info
 * so the dev doesn't have to ask "which build was this?".
 */
object LogExporter {

    private const val PROVIDER_SUFFIX = ".fileprovider"
    private const val MAX_LINES = "2000"

    fun captureToFile(ctx: Context): File {
        val logsDir = File(ctx.cacheDir, "logs").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(logsDir, "memo-log-$ts.txt")

        out.bufferedWriter().use { w ->
            w.append("=== Memo Widget log export ===\n")
            w.append("App: ${BuildConfig.APPLICATION_ID} v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n")
            w.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n")
            w.append("PID: ${Process.myPid()}\n")
            w.append("Captured: ${Date()}\n")
            w.append("=== logcat -d -t $MAX_LINES (own-process only) ===\n\n")

            try {
                val proc = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-d", "-v", "threadtime", "-t", MAX_LINES)
                )
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        w.append(line)
                        w.append('\n')
                    }
                }
                proc.waitFor()
            } catch (e: Exception) {
                w.append("logcat 调用失败：${e.message}\n")
            }

            // ApplicationExitInfo is API 30+. The OS records why the process
            // last died (ANR / NATIVE_CRASH / LOW_MEMORY / SIGNALED / etc.)
            // including ANRs we can't catch ourselves. logcat alone misses
            // these because the process died before it could log.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                w.append("\n\n=== ApplicationExitInfo (system-recorded last 5) ===\n")
                try {
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
                    if (exits.isEmpty()) {
                        w.append("(none)\n")
                    } else {
                        exits.forEach { info ->
                            w.append("--- pid=${info.pid} ts=${Date(info.timestamp)} ---\n")
                            w.append("reason: ${reasonName(info.reason)}\n")
                            w.append("status: ${info.status}\n")
                            w.append("importance: ${info.importance}\n")
                            info.description?.let { w.append("description: $it\n") }
                            w.append('\n')
                        }
                    }
                } catch (e: Exception) {
                    w.append("ApplicationExitInfo 调用失败：${e.message}\n")
                }
            }

            // Concatenate any persisted crash logs at the tail. logcat's
            // circular buffer can lose crashes by the time the user gets
            // around to exporting; CrashLogger writes them to filesDir/crash/
            // so they survive across launches.
            w.append("\n\n=== Persisted crashes (filesDir/crash/) ===\n")
            val crashes = CrashLogger.crashDir(ctx)
                .listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            if (crashes.isEmpty()) {
                w.append("(none)\n")
            } else {
                crashes.forEach { f ->
                    w.append("\n--- ${f.name} ---\n")
                    try {
                        f.useLines { lines ->
                            lines.forEach { line ->
                                w.append(line)
                                w.append('\n')
                            }
                        }
                    } catch (e: Exception) {
                        w.append("(read failed: ${e.message})\n")
                    }
                }
            }
        }
        return out
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java/Kotlin)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason=$reason"
    }

    fun shareIntent(ctx: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            ctx,
            ctx.packageName + PROVIDER_SUFFIX,
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Memo Widget 日志 — ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "导出日志").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
