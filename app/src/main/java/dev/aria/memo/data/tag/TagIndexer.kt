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

    /**
     * Pre-tree match: enough information to install into the tree but
     * cheap to produce. Held in [Cache] so a body whose hash matched
     * the previous emission can skip the regex pass entirely.
     */
    internal data class RawMatch(
        val full: String,
        val date: LocalDate,
        val time: LocalTime,
        val body: String,
    )

    /**
     * Per-VM memo for tag extraction. Holding one of these alive across
     * calls to [index] turns repeated emissions of the same content
     * (the common case — Room flows fire whenever any row changes,
     * so unchanged rows still pass through) into O(1) cache hits.
     *
     * Fixes #124 (Perf-1 H2): writing one note used to re-run the tag
     * regex over every body in the library — at 500 notes that meant
     * meaningful CPU pressure on every keystroke that triggered a save.
     */
    class Cache {
        private val byPath: MutableMap<String, Pair<Int, List<RawMatch>>> = HashMap()
        private val byUid: MutableMap<String, Pair<Int, List<RawMatch>>> = HashMap()

        internal fun forFile(
            path: String,
            contentHash: Int,
            build: () -> List<RawMatch>,
        ): List<RawMatch> {
            val cached = byPath[path]
            if (cached != null && cached.first == contentHash) return cached.second
            val fresh = build()
            byPath[path] = contentHash to fresh
            return fresh
        }

        internal fun forNote(
            uid: String,
            bodyHash: Int,
            build: () -> List<RawMatch>,
        ): List<RawMatch> {
            val cached = byUid[uid]
            if (cached != null && cached.first == bodyHash) return cached.second
            val fresh = build()
            byUid[uid] = bodyHash to fresh
            return fresh
        }

        internal fun retainPaths(paths: Set<String>) { byPath.keys.retainAll(paths) }
        internal fun retainUids(uids: Set<String>) { byUid.keys.retainAll(uids) }
    }

    /**
     * Scan inputs, build the tag tree. Backwards-compatible static
     * shortcut for tests and one-shot callers — production code holding
     * a long-lived [Cache] should call [index] directly.
     */
    fun indexAll(
        files: List<NoteFileEntity>,
        // Bug-1 H11 fix (#115): single-note tags 也要扫,否则用户在 Obsidian-style
        // 单笔记里写 #tag 会"隐身"——TagListScreen 看不到。Default emptyList 保持
        // 调用方兼容(已有 caller 不传 single notes 也能跑)。
        singleNotes: List<SingleNoteEntity> = emptyList(),
    ): TagNode = index(files, singleNotes, cache = null)

    /**
     * Cache-aware variant. When [cache] is non-null, bodies whose hash
     * matches the previous call short-circuit the [TAG_REGEX] pass and
     * reuse stored matches. Cache entries for paths/uids that aren't in
     * the current emission are pruned so the cache stays bounded.
     */
    fun index(
        files: List<NoteFileEntity>,
        singleNotes: List<SingleNoteEntity>,
        cache: Cache?,
    ): TagNode {
        val root = MutableNode(name = "")

        for (file in files) {
            val matches = if (cache != null) {
                cache.forFile(file.path, file.content.hashCode()) {
                    extractFromContent(file.content, file.date)
                }
            } else extractFromContent(file.content, file.date)
            for (m in matches) installMatch(root, m)
        }
        for (note in singleNotes) {
            if (note.tombstoned) continue
            val matches = if (cache != null) {
                cache.forNote(note.uid, note.body.hashCode()) {
                    extractFromBody(note.body, note.date, note.time)
                }
            } else extractFromBody(note.body, note.date, note.time)
            for (m in matches) installMatch(root, m)
        }

        cache?.retainPaths(files.mapTo(HashSet()) { it.path })
        cache?.retainUids(
            singleNotes.asSequence()
                .filterNot { it.tombstoned }
                .mapTo(HashSet()) { it.uid },
        )

        return root.freeze()
    }

    /** Pull every entry body out of a day-file's raw content and run
     *  the tag extractor over each. */
    private fun extractFromContent(content: String, date: LocalDate): List<RawMatch> {
        val entries = MemoRepository.parseEntries(content, date)
        if (entries.isEmpty()) return emptyList()
        val out = ArrayList<RawMatch>()
        for (entry in entries) {
            out += extractFromBody(entry.body, entry.date, entry.time)
        }
        return out
    }

    /** Run [TAG_REGEX] over a single body, dedupe within the body. */
    private fun extractFromBody(body: String, date: LocalDate, time: LocalTime): List<RawMatch> {
        if ('#' !in body) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<RawMatch>()
        for (match in TAG_REGEX.findAll(body)) {
            val full = match.groupValues[1]
            if (!seen.add(full)) continue
            out += RawMatch(full = full, date = date, time = time, body = body)
        }
        return out
    }

    private fun installMatch(root: MutableNode, m: RawMatch) {
        val segments = m.full.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return
        var node = root
        for (seg in segments) {
            node = node.children.getOrPut(seg) { MutableNode(seg) }
        }
        node.entries += TagMatch(date = m.date, time = m.time, body = m.body)
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
