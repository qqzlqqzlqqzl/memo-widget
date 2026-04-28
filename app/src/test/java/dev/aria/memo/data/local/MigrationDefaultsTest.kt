package dev.aria.memo.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tripwire for #301 (Data-1 R1).
 *
 * Room's migration identity hash compares column types and not
 * `DEFAULT` clauses, so a future edit that drops a `DEFAULT 0` in a
 * migration's `CREATE TABLE` / `ALTER TABLE` body will not be caught
 * by Room's schema validator. Subsequent installs that bypass the
 * legacy migration path would then see a column with no default and
 * fail to insert.
 *
 * This test reads `AppDatabase.kt` from the source tree and asserts
 * that every place that historically carried a `DEFAULT 0` still does.
 * If you genuinely intend to remove or change a default, update both
 * the migration AND this expectation list — the failure makes that a
 * deliberate decision rather than an accident.
 */
class MigrationDefaultsTest {

    @Test
    fun `every historical DEFAULT 0 in the migration set survives`() {
        val source = File(SOURCE_PATH)
        assertTrue(
            "AppDatabase.kt must be reachable from the test working dir, " +
                "looked at $SOURCE_PATH (cwd=${File(".").absoluteFile})",
            source.exists(),
        )
        val text = source.readText()
        for (expectation in EXPECTED_DEFAULT_LINES) {
            assertTrue(
                "Expected this DEFAULT clause to still exist in AppDatabase.kt:\n  $expectation\n" +
                    "If you genuinely intended to drop the default, update both the " +
                    "migration body and EXPECTED_DEFAULT_LINES in MigrationDefaultsTest.",
                text.contains(expectation),
            )
        }
    }

    companion object {
        private const val SOURCE_PATH =
            "src/main/java/dev/aria/memo/data/local/AppDatabase.kt"

        /**
         * Each entry is a substring that must appear verbatim in
         * `AppDatabase.kt`. They cover every place a Room migration
         * relied on a column DEFAULT to backfill rows during the
         * upgrade. Order doesn't matter; we just check presence.
         */
        private val EXPECTED_DEFAULT_LINES: List<String> = listOf(
            // v1→2 events.tombstoned DEFAULT 0
            "`tombstoned` INTEGER NOT NULL DEFAULT 0,",
            // v6→7 note_files.isPinned DEFAULT 0
            "ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0",
        )
    }
}
