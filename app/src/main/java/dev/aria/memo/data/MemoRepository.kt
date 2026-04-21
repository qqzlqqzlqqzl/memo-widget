package dev.aria.memo.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Business logic orchestrating [SettingsStore] + [GitHubApi].
 *
 * Contract (AGENT_SPEC.md sections 3 + 4.3):
 *  - [appendToday] appends a `## HH:MM\n{body}\n` block to today's file,
 *    creating the file with a `# YYYY-MM-DD` header if it does not yet exist.
 *    On a 409/422 sha conflict, retries exactly once after re-fetching.
 *  - [recentEntries] reads today's file and returns the last N entries in
 *    reverse chronological order. Missing file → empty list (not an error).
 *
 * This class never throws; every public method returns [MemoResult]. The
 * repository is stateless — safe to share across activities/widget.
 */
class MemoRepository(
    private val settings: SettingsStore,
    private val api: GitHubApi,
) {

    suspend fun appendToday(
        body: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): MemoResult<Unit> {
        val config = settings.current()
        if (!config.isConfigured) {
            return MemoResult.Err(ErrorCode.NOT_CONFIGURED, "PAT/owner/repo missing")
        }

        val date = now.toLocalDate()
        val time = now.toLocalTime()
        val path = config.filePathFor(date)

        val firstAttempt = doAppend(config, path, date, time, body, existingSha = null, fetch = true)
        if (firstAttempt is MemoResult.Err && firstAttempt.code == ErrorCode.CONFLICT) {
            // Retry once with a fresh sha (AGENT_SPEC rule: retry once on CONFLICT).
            return doAppend(config, path, date, time, body, existingSha = null, fetch = true)
        }
        return firstAttempt
    }

    suspend fun recentEntries(limit: Int = 3): MemoResult<List<MemoEntry>> {
        val config = settings.current()
        if (!config.isConfigured) {
            return MemoResult.Err(ErrorCode.NOT_CONFIGURED, "PAT/owner/repo missing")
        }

        val today = LocalDate.now()
        val path = config.filePathFor(today)

        return when (val res = api.getFile(config, path)) {
            is MemoResult.Ok -> {
                val text = runCatching { res.value.decodedContent }
                    .getOrElse { return MemoResult.Err(ErrorCode.UNKNOWN, "decode failed") }
                MemoResult.Ok(parseEntries(text, today).take(limit))
            }
            is MemoResult.Err -> when (res.code) {
                ErrorCode.NOT_FOUND -> MemoResult.Ok(emptyList())
                else -> res
            }
        }
    }

    // --- internals ---------------------------------------------------------

    /**
     * Core append path. Fetches existing content (if [fetch] is true) to get
     * the current sha, builds new content, and PUTs. Returns the raw result
     * so [appendToday] can decide whether to retry.
     */
    private suspend fun doAppend(
        config: AppConfig,
        path: String,
        date: LocalDate,
        time: LocalTime,
        body: String,
        existingSha: String?,
        fetch: Boolean,
    ): MemoResult<Unit> {
        var sha: String? = existingSha
        var existingText: String? = null

        if (fetch) {
            when (val getRes = api.getFile(config, path)) {
                is MemoResult.Ok -> {
                    sha = getRes.value.sha
                    existingText = runCatching { getRes.value.decodedContent }.getOrNull()
                    if (existingText == null) {
                        return MemoResult.Err(ErrorCode.UNKNOWN, "decode existing failed")
                    }
                }
                is MemoResult.Err -> when (getRes.code) {
                    ErrorCode.NOT_FOUND -> { /* creating fresh */ }
                    else -> return getRes // propagate UNAUTHORIZED/NETWORK/etc.
                }
            }
        }

        val newContent = buildNewContent(existingText, date, time, body)
        val encoded = java.util.Base64.getEncoder().encodeToString(
            newContent.toByteArray(Charsets.UTF_8)
        )
        val commitMsg = "memo: ${date} ${HHMM.format(time)}"
        val request = GhPutRequest(
            message = commitMsg,
            content = encoded,
            branch = config.branch,
            sha = sha,
        )

        return when (val putRes = api.putFile(config, path, request)) {
            is MemoResult.Ok -> MemoResult.Ok(Unit)
            is MemoResult.Err -> MemoResult.Err(putRes.code, putRes.message)
        }
    }

    private fun buildNewContent(
        existing: String?,
        date: LocalDate,
        time: LocalTime,
        body: String,
    ): String {
        val hhmm = HHMM.format(time)
        val trimmedBody = body.trimEnd()
        return if (existing.isNullOrBlank()) {
            "# ${date}\n\n## ${hhmm}\n${trimmedBody}\n"
        } else {
            // Ensure exactly one blank line between the previous entry and the new header.
            val base = existing.trimEnd('\n')
            "${base}\n\n## ${hhmm}\n${trimmedBody}\n"
        }
    }

    /**
     * Parse all `## HH:MM` entries out of [text]. Entries are returned in
     * reverse chronological order (newest first) so callers can `take(n)`.
     */
    private fun parseEntries(text: String, date: LocalDate): List<MemoEntry> {
        val regex = Regex("(?m)^## (\\d{2}):(\\d{2})\\s*\\n([\\s\\S]*?)(?=\\n## |\\z)")
        val matches = regex.findAll(text).toList()
        val entries = matches.mapNotNull { m ->
            val hh = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val mm = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (hh !in 0..23 || mm !in 0..59) return@mapNotNull null
            val body = m.groupValues[3].trim('\n').trimEnd()
            MemoEntry(date = date, time = LocalTime.of(hh, mm), body = body)
        }
        return entries.sortedByDescending { it.time }
    }

    private companion object {
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
