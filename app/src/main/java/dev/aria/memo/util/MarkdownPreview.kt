package dev.aria.memo.util

/**
 * Shared helpers for deriving short, display-safe previews from a note body.
 *
 * Extracted in Fix-8 to collapse three near-identical implementations that had
 * diverged over time:
 *  - `SingleNoteRepository.Companion.extractTitle` — first non-empty line,
 *    strip heading / list / quote / numbered-list markers
 *  - `String.firstNonEmptyLineStripped` in `widget/MemoWidget.kt` — same intent,
 *    missing the `^\d+[.)\]]\s*` branch
 *  - `String.firstLinePreview` in `widget/MemoWidgetContent.kt` — compact
 *    widget-cell preview that folds up to the first three non-empty lines
 *    with ` / ` as separator
 *
 * The semantics here are **verbatim** to the widest previous implementation
 * (i.e. [firstNonEmptyLineStripped] now also strips numbered-list markers —
 * MemoWidget's version was strictly a subset of SingleNoteRepository's and
 * always rendered the same thing on the bodies MemoWidget actually sees).
 * [buildPreview] keeps the exact three-line fold behavior it had before.
 *
 * NOTE: `NoteListViewModel.buildPreview` handles YAML front-matter stripping,
 * which is a different concern. That one is intentionally NOT folded in here.
 */
object MarkdownPreview {

    /**
     * Return the first non-empty line of [text] with leading markdown markers
     * removed so the result matches what a user "reads" as a title.
     *
     * Markers stripped (in order):
     *  - `#+\s*` — ATX heading hashes (`# Foo`, `## Foo`)
     *  - `[>\-*+]\s*` — quote / unordered-list markers
     *  - `\d+[.)\]]\s*` — ordered-list markers (`1. Foo`, `2) Foo`, `3] Foo`)
     */
    fun firstNonEmptyLineStripped(text: String): String {
        val first = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return ""
        var t = first
        t = t.replace(Regex("^#+\\s*"), "")
        t = t.replace(Regex("^[>\\-*+]\\s*"), "")
        t = t.replace(Regex("^\\d+[.)\\]]\\s*"), "")
        return t.trim()
    }

    /**
     * Build a compact, single-line preview of a memo body for widget cells.
     *
     * Normalizes common markdown list markers ("- ", "* ") and folds up to the
     * first three non-empty lines, separated by " / ". Designed for glance-sized
     * text where body content rarely fits full lines anyway.
     */
    fun buildPreview(text: String): String {
        val pieces = text.lineSequence()
            .map { line -> line.trim().removePrefix("- ").removePrefix("* ").trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return when (pieces.size) {
            0 -> ""
            1 -> pieces[0]
            else -> pieces.take(3).joinToString(separator = " / ")
        }
    }
}
