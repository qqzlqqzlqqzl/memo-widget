package dev.aria.memo.data.ai

/**
 * How much of the user's own content to prepend to a chat turn as a system
 * prompt.
 *
 * [NONE] — pure chat, nothing injected.
 * [CURRENT_NOTE] — only the note the user is currently viewing/editing.
 * [ALL_NOTES] — every note body the caller hands us. Caller is expected to
 * pre-sort by "most relevant first" (date desc in practice) because we
 * truncate tail-first when the budget is exceeded.
 */
enum class AiContextMode { NONE, CURRENT_NOTE, ALL_NOTES }

/**
 * Builds the `system` message for an [AiClient.chat] call out of the user's
 * own notes.
 *
 * Total size is capped by [charBudget] (default 15 000 chars ≈ 5 000 tokens
 * for English/Chinese mix). Budgets keep the provider bill predictable and
 * avoid 413/context-length errors across providers whose limits differ by
 * 10x (ollama-local vs. GPT-4o).
 *
 * Truncation rules:
 *  - [CURRENT_NOTE] — cut the single body from the *tail* and append
 *    `"...(truncated)"` so the important front-matter survives.
 *  - [ALL_NOTES] — take bodies in the order supplied (caller's job to
 *    front-load the recent ones) and stop as soon as the next one would
 *    overflow. The last included note gets a `"...(truncated)"` if it
 *    itself was clipped.
 */
object AiContextBuilder {

    private const val HEADER_CURRENT = "以下是当前笔记，回答请基于它：\n\n"
    private const val HEADER_ALL = "以下是用户最近的笔记（按时间倒序），回答请基于这些内容：\n\n"
    private const val NOTE_SEPARATOR = "\n\n---\n\n"
    private const val TRUNCATION_SUFFIX = "...(truncated)"

    fun buildSystemPrompt(
        mode: AiContextMode,
        currentNoteBody: String? = null,
        allNoteBodies: List<String> = emptyList(),
        charBudget: Int = 15_000,
    ): String {
        if (charBudget <= 0) return ""
        return when (mode) {
            AiContextMode.NONE -> ""
            AiContextMode.CURRENT_NOTE -> buildCurrent(currentNoteBody.orEmpty(), charBudget)
            AiContextMode.ALL_NOTES -> buildAll(allNoteBodies, charBudget)
        }
    }

    private fun buildCurrent(body: String, charBudget: Int): String {
        if (body.isBlank()) return ""
        val header = HEADER_CURRENT
        val available = charBudget - header.length
        if (available <= 0) return ""
        val clipped = if (body.length <= available) {
            body
        } else {
            val room = (available - TRUNCATION_SUFFIX.length).coerceAtLeast(0)
            body.take(room) + TRUNCATION_SUFFIX
        }
        return header + clipped
    }

    private fun buildAll(bodies: List<String>, charBudget: Int): String {
        val nonEmpty = bodies.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return ""
        val header = HEADER_ALL
        var remaining = charBudget - header.length
        if (remaining <= 0) return ""

        val sb = StringBuilder(header)
        var first = true
        for (body in nonEmpty) {
            val prefixLen = if (first) 0 else NOTE_SEPARATOR.length
            val space = remaining - prefixLen
            if (space <= 0) break
            if (!first) sb.append(NOTE_SEPARATOR)
            if (body.length <= space) {
                sb.append(body)
                remaining -= prefixLen + body.length
            } else {
                // Last body that fits — clip it and stop.
                val room = (space - TRUNCATION_SUFFIX.length).coerceAtLeast(0)
                sb.append(body.take(room)).append(TRUNCATION_SUFFIX)
                remaining = 0
                break
            }
            first = false
        }
        return sb.toString()
    }
}
