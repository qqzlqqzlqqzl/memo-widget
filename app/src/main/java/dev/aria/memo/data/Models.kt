package dev.aria.memo.data

import java.time.LocalDate
import java.time.LocalTime

/**
 * App-wide configuration persisted in [SettingsStore].
 *
 * Matches AGENT_SPEC.md section 2.1. [pat] is the GitHub Personal Access Token;
 * an empty string indicates the app is unconfigured. [filePathFor] renders the
 * per-day file path by substituting `{yyyy}`, `{MM}`, and `{dd}` tokens in
 * [pathTemplate] (zero-padded month and day).
 *
 * Thread-safe by virtue of being an immutable data class.
 */
data class AppConfig(
    val pat: String,
    val owner: String,
    val repo: String,
    val branch: String = "main",
    val pathTemplate: String = "{yyyy}-{MM}-{dd}.md",
) {
    val isConfigured: Boolean
        get() = pat.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()

    fun filePathFor(date: LocalDate): String = pathTemplate
        .replace("{yyyy}", date.year.toString())
        .replace("{MM}", date.monthValue.toString().padStart(2, '0'))
        .replace("{dd}", date.dayOfMonth.toString().padStart(2, '0'))
}

/**
 * A single memo entry parsed from a day's markdown file.
 *
 * [date] is derived from the containing file name; [time] is parsed from a
 * `## HH:MM` header inside the file; [body] contains the markdown between this
 * header and the next one (trimmed of leading/trailing whitespace).
 */
data class MemoEntry(
    val date: LocalDate,
    val time: LocalTime,
    val body: String,
)

/**
 * Result wrapper used by all public repository/API methods.
 *
 * Layers below the UI MUST NOT throw — they return [MemoResult] so callers can
 * render error states without try/catch. See AGENT_SPEC.md section 2.4 and
 * rule 4 in section 7.
 */
sealed class MemoResult<out T> {
    data class Ok<T>(val value: T) : MemoResult<T>()
    data class Err(val code: ErrorCode, val message: String) : MemoResult<Nothing>()
}

/** Machine-readable error classes. Human messages live in [MemoResult.Err.message]. */
enum class ErrorCode {
    NOT_CONFIGURED,
    UNAUTHORIZED,
    NOT_FOUND,
    CONFLICT,
    NETWORK,
    UNKNOWN,
}
