package dev.aria.memo.data.tag

import dev.aria.memo.data.MemoRepository
import dev.aria.memo.data.local.NoteFileEntity
import dev.aria.memo.data.local.SingleNoteEntity
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

    fun indexAll(
        files: List<NoteFileEntity>,
        // Bug-1 H11 fix (#115): single-note tags 也要扫,否则用户在 Obsidian-style
        // 单笔记里写 #tag 会"隐身"——TagListScreen 看不到。Default emptyList 保持
        // 调用方兼容(已有 caller 不传 single notes 也能跑)。
        singleNotes: List<SingleNoteEntity> = emptyList(),
    ): TagNode {
        val root = MutableNode(name = "")

        for (file in files) {
            val entries = MemoRepository.parseEntries(file.content, file.date)
            for (entry in entries) {
                indexBody(root, entry.body, entry.date, entry.time)
            }
        }
        // Bug-1 H11 fix: 同样规则扫 single-note body。
        for (note in singleNotes) {
            if (note.tombstoned) continue
            indexBody(root, note.body, note.date, note.time)
        }

        return root.freeze()
    }

    private fun indexBody(root: MutableNode, body: String, date: LocalDate, time: LocalTime) {
        val seen = HashSet<String>()
        for (match in TAG_REGEX.findAll(body)) {
            val full = match.groupValues[1]
            if (!seen.add(full)) continue
            val segments = full.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            var node = root
            for (seg in segments) {
                node = node.children.getOrPut(seg) { MutableNode(seg) }
            }
            node.entries += TagMatch(date = date, time = time, body = body)
        }
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
