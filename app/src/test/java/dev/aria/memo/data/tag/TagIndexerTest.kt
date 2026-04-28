package dev.aria.memo.data.tag

import dev.aria.memo.data.local.NoteFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TagIndexerTest {

    private fun note(date: LocalDate, body: String): NoteFileEntity {
        val content = "# $date\n\n## 09:30\n$body\n"
        return NoteFileEntity(
            path = "${date}.md",
            date = date,
            content = content,
            githubSha = null,
            localUpdatedAt = 0L,
            remoteUpdatedAt = null,
            dirty = false,
        )
    }

    private fun findChild(root: TagNode, path: String): TagNode? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        var cur: TagNode = root
        for (seg in segments) {
            cur = cur.children.firstOrNull { it.name == seg } ?: return null
        }
        return cur
    }

    @Test
    fun `single top-level tag is indexed`() {
        val root = TagIndexer.indexAll(
            listOf(note(LocalDate.of(2026, 4, 21), "finished the quarterly review #work"))
        )
        val work = findChild(root, "work")
        assertNotNull("expected #work node", work)
        assertTrue("no nested children expected", work!!.children.isEmpty())
        assertEquals(1, work.entries.size)
        assertEquals(LocalDate.of(2026, 4, 21), work.entries[0].date)
    }

    @Test
    fun `nested tag builds tree path`() {
        val root = TagIndexer.indexAll(
            listOf(note(LocalDate.of(2026, 4, 21), "stand-up sync #work/meeting"))
        )
        val work = findChild(root, "work")
        assertNotNull(work)
        // The tag path ends at `meeting`, so the match should live there — not on `work`.
        assertTrue("parent should have no direct entries", work!!.entries.isEmpty())
        val meeting = findChild(root, "work/meeting")
        assertNotNull(meeting)
        assertEquals(1, meeting!!.entries.size)
    }

    @Test
    fun `CJK tag characters are supported`() {
        val root = TagIndexer.indexAll(
            listOf(note(LocalDate.of(2026, 4, 21), "讨论需求 #工作/开会"))
        )
        val cjkRoot = findChild(root, "工作")
        assertNotNull("should find Chinese top-level tag", cjkRoot)
        val cjkChild = findChild(root, "工作/开会")
        assertNotNull("should find Chinese nested tag", cjkChild)
        assertEquals(1, cjkChild!!.entries.size)
    }

    @Test
    fun `multiple notes contribute to same tag node`() {
        val root = TagIndexer.indexAll(
            listOf(
                note(LocalDate.of(2026, 4, 19), "a #todo"),
                note(LocalDate.of(2026, 4, 20), "b #todo"),
                note(LocalDate.of(2026, 4, 21), "c #todo"),
            )
        )
        val todo = findChild(root, "todo")
        assertNotNull(todo)
        assertEquals(3, todo!!.entries.size)
        // Newest first — 4/21, 4/20, 4/19.
        assertEquals(LocalDate.of(2026, 4, 21), todo.entries[0].date)
        assertEquals(LocalDate.of(2026, 4, 19), todo.entries[2].date)
    }

    @Test
    fun `plain body without hashtags produces empty tree`() {
        val root = TagIndexer.indexAll(
            listOf(note(LocalDate.of(2026, 4, 21), "just a normal note, no tags at all"))
        )
        assertTrue("root should have zero children", root.children.isEmpty())
        assertNull(findChild(root, "work"))
    }

    @Test
    fun `tag inside code fence is still indexed MVP limitation`() {
        // MVP: we do NOT strip fenced code blocks. This test pins the current
        // behaviour so a future change that adds code-block filtering is an
        // explicit decision.
        val body = "see snippet:\n```\n// #fakeTag\n```\nand also #realTag"
        val root = TagIndexer.indexAll(
            listOf(note(LocalDate.of(2026, 4, 21), body))
        )
        assertNotNull("real tag should be indexed", findChild(root, "realTag"))
        assertNotNull(
            "code-block tag is NOT filtered (MVP limitation)",
            findChild(root, "fakeTag"),
        )
    }

    @Test
    fun `cache returns the same tree shape for repeated unchanged input`() {
        // Issue #124: tag indexing must be idempotent under the cache —
        // two consecutive index() calls with identical inputs must
        // produce identical trees, otherwise stale cached matches would
        // leak as duplicates.
        val cache = TagIndexer.Cache()
        val files = listOf(
            note(LocalDate.of(2026, 4, 21), "morning #work/meeting"),
            note(LocalDate.of(2026, 4, 22), "evening #personal #work"),
        )
        val first = TagIndexer.index(files, emptyList(), cache)
        val second = TagIndexer.index(files, emptyList(), cache)
        // Same set of root children, same names.
        assertEquals(
            first.children.map { it.name }.sorted(),
            second.children.map { it.name }.sorted(),
        )
        // No duplicate entries — the cache must not double-count matches.
        val workNode1 = findChild(first, "work")
        val workNode2 = findChild(second, "work")
        assertEquals(workNode1!!.entries.size, workNode2!!.entries.size)
        assertTrue(workNode1.entries.size > 0)
    }

    @Test
    fun `cache evicts deleted paths so removed files do not linger`() {
        // Issue #124: when a file vanishes from the input, the cache slot
        // for it must be cleared too — otherwise the next emission's
        // tree would still carry its tags.
        val cache = TagIndexer.Cache()
        val firstSet = listOf(
            note(LocalDate.of(2026, 4, 21), "before #legacy"),
            note(LocalDate.of(2026, 4, 22), "stays #pinned"),
        )
        val before = TagIndexer.index(firstSet, emptyList(), cache)
        assertNotNull(findChild(before, "legacy"))
        assertNotNull(findChild(before, "pinned"))

        // Drop the first file; only #pinned should survive.
        val secondSet = listOf(firstSet[1])
        val after = TagIndexer.index(secondSet, emptyList(), cache)
        assertNull(
            "deleted file's tag must not survive in the next tree",
            findChild(after, "legacy"),
        )
        assertNotNull(findChild(after, "pinned"))
    }
}
