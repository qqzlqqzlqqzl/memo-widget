package dev.aria.memo.data

import android.content.Context
import dev.aria.memo.data.local.NoteDao
import dev.aria.memo.data.local.NoteFileEntity
import dev.aria.memo.data.sync.PathLocker
import dev.aria.memo.data.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Business logic orchestrating [SettingsStore] + [GitHubApi] + Room.
 *
 * V2 contract (P1):
 *  - [appendToday] writes the new entry to Room first (always succeeds if the
 *    app is configured), then tries a synchronous push. Any failure enqueues
 *    [SyncScheduler.enqueuePush] so the dirty row is retried in the background.
 *  - [recentEntries] reads from Room (offline-safe). [refreshNow] can be
 *    called to pull remote changes.
 *  - [observeNotes] is a live Flow over every cached day-file, newest first,
 *    for the notes-list screen.
 */
class MemoRepository(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val api: GitHubApi,
    private val dao: NoteDao,
) {

    fun observeNotes(): Flow<List<NoteFileEntity>> = dao.observeAll()

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

        // Fixes #6: hold the per-path lock across read-merge-write-push so a
        // parallel PushWorker cannot race us on the same SHA.
        return PathLocker.withLock(path) {
            // 1. Local-first: merge the new entry into whatever we have cached.
            val existing = dao.get(path)
            val newContent = buildNewContent(existing?.content, date, time, body)
            val nowMs = System.currentTimeMillis()
            dao.upsert(
                NoteFileEntity(
                    path = path,
                    date = date,
                    content = newContent,
                    githubSha = existing?.githubSha,
                    localUpdatedAt = nowMs,
                    remoteUpdatedAt = existing?.remoteUpdatedAt,
                    dirty = true,
                )
            )

            // 2. Best-effort synchronous push. Whatever the outcome, enqueue
            //    the background worker so a stuck dirty row always has a retry.
            val pushResult = pushFile(config, path, newContent, existing?.githubSha)
            when (pushResult) {
                is MemoResult.Ok -> {
                    dao.markClean(path, pushResult.value, nowMs)
                    MemoResult.Ok(Unit)
                }
                is MemoResult.Err -> {
                    SyncScheduler.enqueuePush(appContext)
                    when (pushResult.code) {
                        ErrorCode.NETWORK, ErrorCode.CONFLICT -> MemoResult.Ok(Unit)
                        else -> pushResult
                    }
                }
            }
        }
    }

    suspend fun recentEntries(limit: Int = 3): MemoResult<List<MemoEntry>> {
        val config = settings.current()
        if (!config.isConfigured) {
            return MemoResult.Err(ErrorCode.NOT_CONFIGURED, "PAT/owner/repo missing")
        }
        val today = LocalDate.now()
        val path = config.filePathFor(today)
        val cached = dao.get(path) ?: return MemoResult.Ok(emptyList())
        return MemoResult.Ok(parseEntries(cached.content, today).take(limit))
    }

    /** One-shot refresh from GitHub; schedules a WorkManager job. */
    fun refreshNow() = SyncScheduler.enqueuePullNow(appContext)

    /** Flush any dirty rows left over from a crashed or unauthorized push. */
    fun kickPendingPush() = SyncScheduler.enqueuePush(appContext)

    // --- internals ---------------------------------------------------------

    private suspend fun pushFile(
        config: AppConfig,
        path: String,
        content: String,
        knownSha: String?,
    ): MemoResult<String> {
        val sha = knownSha ?: when (val getRes = api.getFile(config, path)) {
            is MemoResult.Ok -> getRes.value.sha
            is MemoResult.Err -> when (getRes.code) {
                ErrorCode.NOT_FOUND -> null
                else -> return MemoResult.Err(getRes.code, getRes.message)
            }
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val request = GhPutRequest(
            message = "memo: $path",
            content = encoded,
            branch = config.branch,
            sha = sha,
        )
        return when (val putRes = api.putFile(config, path, request)) {
            is MemoResult.Ok -> MemoResult.Ok(putRes.value.content.sha)
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
            val base = existing.trimEnd('\n')
            "${base}\n\n## ${hhmm}\n${trimmedBody}\n"
        }
    }

    /**
     * Toggle the pin flag on the given day-file. Updates Room, rewrites the
     * markdown front matter so it round-trips through GitHub, marks the row
     * dirty, and enqueues a push. No-op if the file isn't in Room yet.
     */
    suspend fun togglePin(path: String, pinned: Boolean) {
        PathLocker.withLock(path) {
            val existing = dao.get(path) ?: return@withLock
            val newContent = applyPinFrontMatter(existing.content, pinned)
            dao.togglePin(
                path = path,
                pinned = pinned,
                content = newContent,
                updatedAt = System.currentTimeMillis(),
            )
            SyncScheduler.enqueuePush(appContext)
        }
    }

    companion object {
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * Strip a leading `---\n...\n---\n` YAML block (if any) from [text] and
         * return what remains. Tolerant of CRLF and trailing blank lines after
         * the closing fence.
         *
         * IMPORTANT: this only strips *real* front matter — i.e. every non-blank
         * line between the two `---` fences must be a `key: value` entry (simple
         * YAML subset) **and** the block must carry a `pinned:` key. Anything
         * else (e.g. a user-authored markdown horizontal rule followed later by
         * another `---`) is left untouched to avoid eating content.
         */
        internal fun stripFrontMatter(text: String): String {
            val normalized = text.replace("\r\n", "\n")
            if (!normalized.startsWith("---\n") && normalized != "---") return text
            // Look for the closing `---` on its own line after the opener.
            val afterOpen = normalized.indexOf('\n') + 1
            val closeMarker = normalized.indexOf("\n---", afterOpen)
            if (closeMarker < 0) return text
            val block = normalized.substring(afterOpen, closeMarker)
            if (!looksLikePinFrontMatter(block)) return text
            // Skip past `\n---` and any blank line(s) that sit between the
            // fence and the body. [applyPinFrontMatter] inserts exactly one,
            // but be tolerant of zero or more for hand-edited files.
            var cut = closeMarker + "\n---".length
            while (cut < normalized.length && normalized[cut] == '\n') cut++
            return normalized.substring(cut)
        }

        /**
         * Recognise our own YAML block versus a stray `---` HR the user typed.
         * Requires every non-blank line to look like `key: value` AND at least
         * one line to carry the `pinned` key — otherwise the block is treated
         * as user content and left alone.
         */
        private fun looksLikePinFrontMatter(block: String): Boolean {
            if (block.isEmpty()) return false
            var sawPinned = false
            for (raw in block.split('\n')) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val idx = line.indexOf(':')
                if (idx <= 0) return false
                val key = line.substring(0, idx).trim()
                // A valid simple-YAML key: letters, digits, underscore, hyphen.
                if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                    return false
                }
                if (key == "pinned") {
                    // Severe fix: only treat this as OUR front matter when the
                    // pinned value is a boolean literal. Previously accepting
                    // `pinned: yes` / `pinned: 1` could strip user-authored
                    // YAML blocks that happen to contain a "pinned" key with a
                    // non-bool value, silently deleting the user's other keys.
                    val value = line.substring(idx + 1).trim().trim('"', '\'')
                    if (!value.equals("true", ignoreCase = true) &&
                        !value.equals("false", ignoreCase = true)) {
                        return false
                    }
                    sawPinned = true
                }
            }
            return sawPinned
        }

        /**
         * Return true when [text] currently carries a front matter block with
         * `pinned: true`. Tolerates quoted values and extra whitespace.
         */
        internal fun readPinnedFromFrontMatter(text: String): Boolean {
            val normalized = text.replace("\r\n", "\n")
            if (!normalized.startsWith("---\n")) return false
            val close = normalized.indexOf("\n---", 4)
            if (close < 0) return false
            val block = normalized.substring(4, close)
            for (raw in block.split('\n')) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().trim('"', '\'')
                if (key == "pinned") return value.equals("true", ignoreCase = true)
            }
            return false
        }

        /**
         * Rewrite [content] so it has exactly the right front matter for
         * [pinned]. Pinning prepends a 3-line YAML block; unpinning strips any
         * existing block. Content that already matches is returned untouched.
         */
        internal fun applyPinFrontMatter(content: String, pinned: Boolean): String {
            val stripped = stripFrontMatter(content)
            return if (pinned) {
                // Ensure exactly one blank line between front matter and body.
                val body = stripped.trimStart('\n')
                "---\npinned: true\n---\n\n${body}".trimEnd('\n') + "\n"
            } else {
                stripped
            }
        }

        fun parseEntries(text: String, date: LocalDate): List<MemoEntry> {
            val body = stripFrontMatter(text)
            val regex = Regex("(?m)^## (\\d{2}):(\\d{2})\\s*\\n([\\s\\S]*?)(?=\\n## |\\z)")
            return regex.findAll(body).mapNotNull { m ->
                val hh = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val mm = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (hh !in 0..23 || mm !in 0..59) return@mapNotNull null
                val b = m.groupValues[3].trim('\n').trimEnd()
                MemoEntry(date = date, time = LocalTime.of(hh, mm), body = b)
            }.sortedByDescending { it.time }.toList()
        }

        /**
         * Regex used by [toggleTodoLine] to recognise a single markdown checkbox
         * line. Kept in the companion so tests can pin the exact pattern without
         * importing the UI-layer parser.
         */
        private val TOGGLE_LINE_REGEX = Regex("""^([ \t]*)- \[([ xX])] (.*)$""")
    }

    // --- checklist toggle --------------------------------------------------
    //
    // p3-polish: the edit screen renders each `- [ ] foo` line as a live
    // Checkbox. Tapping it flips the box in-place and persists the whole
    // file. Unlike [appendToday] this does NOT append — it surgically rewrites
    // one line, then pushes the full new content via the same SHA-tracked
    // PUT path. Same locking, same retry-on-failure via PushWorker.
    //
    // Contract:
    //  - If [path] has no cached Room row, returns NOT_FOUND with no side effect.
    //  - If [lineIndex] is out of bounds or doesn't match `- [([ xX])] ...`,
    //    returns UNKNOWN (the UI clicked a checkbox that no longer exists —
    //    shouldn't happen in practice, but we don't silently corrupt the file).
    //  - Otherwise: flips the bracket, upserts as dirty, tries sync PUT, and
    //    enqueues PushWorker on failure (content is safe in Room either way).
    //
    //  Concurrency guard (p3-polish #3): callers must pass the raw line text
    //  they rendered against. If Room content has drifted (e.g. PullWorker
    //  landed a new revision mid-tap) the indexed line no longer matches and
    //  we refuse the toggle with STALE_VIEW, letting the UI re-render cleanly
    //  instead of corrupting the freshly-pulled body.
    suspend fun toggleTodoLine(
        path: String,
        lineIndex: Int,
        expectedRawLine: String,
        newChecked: Boolean,
    ): MemoResult<Unit> {
        val config = settings.current()
        if (!config.isConfigured) {
            return MemoResult.Err(ErrorCode.NOT_CONFIGURED, "PAT/owner/repo missing")
        }
        return PathLocker.withLock(path) {
            val existing = dao.get(path)
                ?: return@withLock MemoResult.Err(ErrorCode.NOT_FOUND, "note not in cache: $path")
            val lines = existing.content.split("\n").toMutableList()
            if (lineIndex !in lines.indices) {
                return@withLock MemoResult.Err(
                    ErrorCode.CONFLICT,
                    "line index out of range after refresh: $lineIndex",
                )
            }
            val original = lines[lineIndex]
            if (original != expectedRawLine) {
                return@withLock MemoResult.Err(
                    ErrorCode.CONFLICT,
                    "line changed since render",
                )
            }
            val match = TOGGLE_LINE_REGEX.matchEntire(original)
                ?: return@withLock MemoResult.Err(
                    ErrorCode.UNKNOWN,
                    "line $lineIndex is not a checklist line",
                )
            val indent = match.groupValues[1]
            val text = match.groupValues[3]
            val mark = if (newChecked) "x" else " "
            lines[lineIndex] = "$indent- [$mark] $text"
            val newContent = lines.joinToString("\n")

            val nowMs = System.currentTimeMillis()
            dao.upsert(
                existing.copy(
                    content = newContent,
                    localUpdatedAt = nowMs,
                    dirty = true,
                )
            )

            val pushResult = pushFile(config, path, newContent, existing.githubSha)
            when (pushResult) {
                is MemoResult.Ok -> {
                    dao.markClean(path, pushResult.value, nowMs)
                    MemoResult.Ok(Unit)
                }
                is MemoResult.Err -> {
                    SyncScheduler.enqueuePush(appContext)
                    when (pushResult.code) {
                        ErrorCode.NETWORK, ErrorCode.CONFLICT -> MemoResult.Ok(Unit)
                        else -> pushResult
                    }
                }
            }
        }
    }
}
