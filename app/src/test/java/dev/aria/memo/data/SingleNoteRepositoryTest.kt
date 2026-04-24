package dev.aria.memo.data

import dev.aria.memo.data.SingleNoteRepository.Companion.buildEntityForCreate
import dev.aria.memo.data.SingleNoteRepository.Companion.extractTitle
import dev.aria.memo.data.SingleNoteRepository.Companion.formatDate
import dev.aria.memo.data.SingleNoteRepository.Companion.formatTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Pure JVM tests for the Obsidian-style single-note repository's companion
 * helpers. We exercise the factory that [SingleNoteRepository.create] uses
 * under the hood (title extraction, slug/filename composition, entity shape)
 * without constructing a live repository — a live one would need an Android
 * [android.content.Context] for WorkManager, which would pull in Robolectric.
 *
 * The repository's CRUD methods are thin pass-throughs around this factory
 * + a DAO upsert + [dev.aria.memo.data.sync.SyncScheduler.enqueuePush]; the
 * instrumented tests in androidTest cover the I/O path.
 */
class SingleNoteRepositoryTest {

    private val fixedNow: LocalDateTime = LocalDateTime.of(2026, 4, 22, 9, 15, 42, 123_000_000)
    private val fixedNowMs: Long = 1_713_770_142_000L
    private val fixedUid: String = "uid-1111-2222"

    @Test
    fun `create derives filename from date time and slug`() {
        val entity = buildEntityForCreate(
            body = "# 早晨想法\n\n今天想到要把 ...",
            now = fixedNow,
            uid = fixedUid,
            nowMs = fixedNowMs,
        )
        assertEquals("notes/2026-04-22-0915-早晨想法.md", entity.filePath)
        assertEquals("早晨想法", entity.title)
        assertEquals(LocalDate.of(2026, 4, 22), entity.date)
        // Seconds/nanos must be zeroed — filename granularity is minute.
        assertEquals(LocalTime.of(9, 15), entity.time)
    }

    @Test
    fun `create marks the entity dirty with null githubSha`() {
        val entity = buildEntityForCreate(
            body = "hello",
            now = fixedNow,
            uid = fixedUid,
            nowMs = fixedNowMs,
        )
        assertTrue("fresh note must be dirty so PushWorker picks it up", entity.dirty)
        assertEquals(null, entity.githubSha)
        assertEquals(null, entity.remoteUpdatedAt)
        assertFalse("fresh note never starts pinned", entity.isPinned)
        assertFalse("fresh note never starts tombstoned", entity.tombstoned)
        assertEquals(fixedNowMs, entity.localUpdatedAt)
    }

    @Test
    fun `create falls back to the default slug when body is blank`() {
        val entity = buildEntityForCreate(
            body = "   \n\n",
            now = fixedNow,
            uid = fixedUid,
            nowMs = fixedNowMs,
        )
        // R11 (Fix-4 P8): blank-body slug now carries a 5-digit suffix to avoid
        // UNIQUE-index collisions when two empty notes share the same minute.
        val path = entity.filePath
        assertTrue("expected note prefix, got $path", path.startsWith("notes/2026-04-22-0915-note-"))
        assertTrue("expected .md suffix, got $path", path.endsWith(".md"))
        assertEquals("", entity.title)
    }

    @Test
    fun `create strips path separators and windows-reserved chars from slug`() {
        // User pastes a title with slashes — must NOT survive into the path,
        // or we'd accidentally create notes/<prefix>/<slug>.md subdirectories.
        val entity = buildEntityForCreate(
            body = "foo/bar?baz<quux>",
            now = fixedNow,
            uid = fixedUid,
            nowMs = fixedNowMs,
        )
        // Slash is converted to a `-` collapsing through the whitespace step.
        val fileName = entity.filePath.removePrefix("notes/")
        assertFalse("slug must not contain '/'", fileName.contains('/'))
        assertFalse("slug must not contain '\\'", fileName.contains('\\'))
        assertTrue("path must still be under notes/", entity.filePath.startsWith("notes/"))
    }

    @Test
    fun `extractTitle strips markdown heading hash`() {
        assertEquals("会议记录", extractTitle("# 会议记录\n\nbody"))
        assertEquals("会议记录", extractTitle("## 会议记录"))
    }

    @Test
    fun `formatDate and formatTime pad with zeros`() {
        assertEquals("2026-01-02", formatDate(LocalDate.of(2026, 1, 2)))
        assertEquals("0903", formatTime(LocalTime.of(9, 3)))
        assertEquals("2330", formatTime(LocalTime.of(23, 30)))
    }

    @Test
    fun `entity localUpdatedAt matches the supplied nowMs`() {
        val a = buildEntityForCreate(
            body = "hello",
            now = fixedNow,
            uid = "a",
            nowMs = 1_000L,
        )
        val b = buildEntityForCreate(
            body = "hello",
            now = fixedNow,
            uid = "b",
            nowMs = 9_999_999L,
        )
        assertEquals(1_000L, a.localUpdatedAt)
        assertEquals(9_999_999L, b.localUpdatedAt)
    }
}
