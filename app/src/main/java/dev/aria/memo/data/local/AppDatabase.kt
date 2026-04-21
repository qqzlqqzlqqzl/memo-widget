package dev.aria.memo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NoteFileEntity::class, EventEntity::class],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun eventDao(): EventDao

    companion object {
        /** v1 → v2: add the events table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `events` (
                        `uid` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `startEpochMs` INTEGER NOT NULL,
                        `endEpochMs` INTEGER NOT NULL,
                        `allDay` INTEGER NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `githubSha` TEXT,
                        `localUpdatedAt` INTEGER NOT NULL,
                        `remoteUpdatedAt` INTEGER,
                        `dirty` INTEGER NOT NULL,
                        `tombstoned` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`uid`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v2 → v3: unique index on filePath (Fixes #2). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_events_filePath` ON `events` (`filePath`)")
            }
        }

        /** v3 → v4: add rrule column for recurring events (P4). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `events` ADD COLUMN `rrule` TEXT")
            }
        }

        /** v4 → v5: add reminderMinutesBefore column for local notifications (P4.1). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `events` ADD COLUMN `reminderMinutesBefore` INTEGER")
            }
        }

        /** v5 → v6: index on note_files.date for calendar/today range queries (Fixes #29). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_files_date` ON `note_files` (`date`)")
            }
        }

        /** v6 → v7: add isPinned column so users can pin day-files to the top (P3). */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE note_files ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "memo.db",
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
    }
}
