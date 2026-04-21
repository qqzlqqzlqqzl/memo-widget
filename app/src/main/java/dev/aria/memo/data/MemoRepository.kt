package dev.aria.memo.data

import android.content.Context
import dev.aria.memo.data.local.NoteDao
import dev.aria.memo.data.local.NoteFileEntity
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

        // 1. Local-first: merge new entry into whatever we have cached.
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

        // 2. Best-effort synchronous push. Whatever the outcome, enqueue the
        //    background worker so a stuck dirty row always has a retry path.
        val pushResult = pushFile(config, path, newContent, existing?.githubSha)
        return when (pushResult) {
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

    companion object {
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun parseEntries(text: String, date: LocalDate): List<MemoEntry> {
            val regex = Regex("(?m)^## (\\d{2}):(\\d{2})\\s*\\n([\\s\\S]*?)(?=\\n## |\\z)")
            return regex.findAll(text).mapNotNull { m ->
                val hh = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val mm = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (hh !in 0..23 || mm !in 0..59) return@mapNotNull null
                val b = m.groupValues[3].trim('\n').trimEnd()
                MemoEntry(date = date, time = LocalTime.of(hh, mm), body = b)
            }.sortedByDescending { it.time }.toList()
        }
    }
}
