package dev.aria.memo.data.tag

import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.local.NoteFileEntity
import java.time.LocalDate
import java.time.LocalTime

/**
 * One match for a nested tag inside a specific memo entry.
 *
 * [date] is the containing day-file's date, [time] the `## HH:MM` header, and
 * [body] is the entry body (trimmed). Multiple tags in the same entry produce
 * multiple [TagMatch] values — one per tag instance.
 */
data class TagMatch(
    val date: LocalDate,
    val time: LocalTime,
    val body: String,
)

/**
 * A node in the nested-tag tree.
 *
 * [name] is the segment at this level — e.g. for `#work/meeting` the root has
 * `name = ""`, a child `work`, and a grandchild `meeting`. [children] is
 * already sorted alphabetically; [entries] holds all matches whose tag path
 * ends exactly at this node (so `#work/meeting` contributes to the `meeting`
 * node's entries, not to `work`'s).
 */
data class TagNode(
    val name: String,
    val children: List<TagNode>,
    val entries: List<TagMatch>,
)

/**
 * Scans all cached day-files, extracts nested tags like `#work/meeting` (incl.
 * CJK) from each entry body, and returns a tag tree rooted at an anonymous
 * node whose children are the top-level tags.
 *
 * Known limitation (MVP): tags inside fenced `` ``` `` markdown code blocks are
 * NOT filtered out — they count like any other tag. Good enough for v1.
 */
object TagIndexer {

    // Allow ASCII letters, digits, underscore, CJK (一-龥), and `/` for nesting.
    // We match the whole tag first, then split on `/` to get the segments, so
    // `#work/meeting/2026` is parsed as a 3-level path.
    private val TAG_REGEX = Regex("#([A-Za-z0-9_\\u4e00-\\u9fa5]+(?:/[A-Za-z0-9_\\u4e00-\\u9fa5]+)*)")

    fun indexAll(files: List<NoteFileEntity>): TagNode {
        // Mutable tree keyed by segment name. We build it bottom-up then freeze
        // into immutable TagNodes at the end.
        val root = MutableNode(name = "")

        for (file in files) {
            val entries = MemoRepository.parseEntries(file.content, file.date)
            for (entry in entries) {
                val seen = HashSet<String>()
                for (match in TAG_REGEX.findAll(entry.body)) {
                    val full = match.groupValues[1]
                    // De-dupe identical tag mentions inside the same entry — one
                    // match per (entry, tag path) is enough for UI purposes.
                    if (!seen.add(full)) continue
                    val segments = full.split('/').filter { it.isNotEmpty() }
                    if (segments.isEmpty()) continue
                    var node = root
                    for (seg in segments) {
                        node = node.children.getOrPut(seg) { MutableNode(seg) }
                    }
                    node.entries += TagMatch(
                        date = entry.date,
                        time = entry.time,
                        body = entry.body,
                    )
                }
            }
        }

        return root.freeze()
    }

    private class MutableNode(val name: String) {
        val children: MutableMap<String, MutableNode> = linkedMapOf()
        val entries: MutableList<TagMatch> = mutableListOf()

        fun freeze(): TagNode = TagNode(
            name = name,
            children = children.values
                .map { it.freeze() }
                .sortedBy { it.name },
            // Newest entries first — matches the note-list UI ordering.
            entries = entries.sortedWith(
                compareByDescending<TagMatch> { it.date }.thenByDescending { it.time }
            ),
        )
    }
}
