package dev.aria.memo.data.notes

/**
 * Derives a filename-safe "slug" from the first line of a note body so each
 * single-note file (Obsidian style) can land at `notes/YYYY-MM-DD-HHMM-<slug>.md`.
 *
 * Design notes:
 *  - Slug is computed from the body's first **non-blank** line; leading blank
 *    lines are skipped so "\n\n\n正文" still produces a useful slug.
 *  - Markdown leading-syntax is stripped (`#` / `##` / `>` / `-` / `*` / `+`
 *    / ordered list numbering + dot). Inline formatting markers (`*_`)
 *    are also dropped — the slug should look like the *rendered* title.
 *  - Path separators (`/`, `\`), control characters, and the handful of
 *    characters that are unsafe on FAT/NTFS (`:`, `?`, `*`, `"`, `<`, `>`,
 *    `|`) are removed so the slug is a portable filename fragment.
 *  - Whitespace runs collapse to a single `-` so the slug is a shell-friendly
 *    token; trailing/leading dashes are trimmed.
 *  - The result is truncated to [MAX_SLUG_CHARS] **characters** (not UTF-8
 *    bytes) — the GitHub Contents API already handles UTF-8 paths, and users
 *    type in their own language.
 *  - Empty body / whitespace-only body → [DEFAULT_SLUG] so we still produce a
 *    valid unique filename. The caller's `HHMM` component guarantees global
 *    uniqueness within the same day.
 *
 * Casing is preserved. Callers that want lower-case can `.lowercase()` the
 * return value; we keep mixed case because Chinese/日本語 users would lose
 * readability under a blanket `lowercase()` call.
 */
object NoteSlugger {

    const val MAX_SLUG_CHARS = 30
    const val DEFAULT_SLUG = "note"

    /**
     * Return a filename-safe slug derived from the first non-blank line of
     * [body]. Never returns an empty string — falls back to [DEFAULT_SLUG].
     */
    fun slugOf(body: String): String {
        val firstLine = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return DEFAULT_SLUG
        val stripped = stripMarkdownLeading(firstLine)
        val cleaned = removeUnsafe(stripped)
        val collapsed = collapseWhitespace(cleaned)
        val trimmed = collapsed.trim('-')
        if (trimmed.isEmpty()) return DEFAULT_SLUG
        // Truncate by codepoint count — avoids splitting a multi-codepoint
        // grapheme cluster mid-emoji, which would otherwise produce an
        // invalid UTF-16 surrogate half.
        val truncated = if (trimmed.length <= MAX_SLUG_CHARS) trimmed else {
            val sb = StringBuilder()
            var i = 0
            var counted = 0
            while (i < trimmed.length && counted < MAX_SLUG_CHARS) {
                val cp = trimmed.codePointAt(i)
                sb.appendCodePoint(cp)
                i += Character.charCount(cp)
                counted++
            }
            sb.toString()
        }
        return truncated.trim('-').ifEmpty { DEFAULT_SLUG }
    }

    // --- internals ---------------------------------------------------------

    /**
     * Strip leading markdown syntax from a single line (headings, quotes,
     * list bullets, ordered-list numbers). Also drops inline emphasis
     * markers so `**bold**` becomes `bold` in the slug.
     */
    private fun stripMarkdownLeading(line: String): String {
        var current = line.trimStart()
        // Heading: one-or-more `#` followed by space.
        current = current.replace(Regex("^#+\\s*"), "")
        // Block quote or bullet: `>`, `-`, `*`, `+` followed by space.
        current = current.replace(Regex("^[>\\-*+]\\s*"), "")
        // Ordered list: digits then `.` or `)` then space.
        current = current.replace(Regex("^\\d+[.)\\]]\\s*"), "")
        // Drop inline emphasis markers, including `**bold**`, `*italic*`,
        // `__under__`, `_it_`, and backticks for `code`.
        current = current.replace(Regex("[*_`]+"), "")
        return current.trim()
    }

    /**
     * Remove characters unsafe for filenames on any of the major filesystems
     * we may round-trip through (FAT, NTFS, ext4, APFS, GitHub paths). Also
     * strips ASCII control chars — they have no place in a filename.
     */
    private fun removeUnsafe(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                // Path separators
                ch == '/' || ch == '\\' -> sb.append(' ')
                // Reserved on Windows + generally a bad idea in shells
                ch == ':' || ch == '?' || ch == '*' || ch == '"' ||
                ch == '<' || ch == '>' || ch == '|' -> sb.append(' ')
                // Control chars (including \n/\r/\t — shouldn't show up after
                // the first-line extraction, but belt-and-suspenders).
                ch.isISOControl() -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Collapse runs of whitespace into a single `-` so the slug is one token. */
    private fun collapseWhitespace(text: String): String =
        text.replace(Regex("\\s+"), "-")
}
