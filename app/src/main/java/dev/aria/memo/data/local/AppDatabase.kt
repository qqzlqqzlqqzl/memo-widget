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
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun eventDao(): EventDao

    companion object {
        /**
         * v1 → v2: add the `events` table. Keeps every existing note_files
         * row intact so users upgrading from P1 don't lose unpushed work.
         */
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

        /**
         * v2 → v3: unique index on `events.filePath` (Fixes #2). Events are keyed
         * on uid in Room, but the sync layer locates them by their remote path;
         * the unique index keeps those two identities in lockstep.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_events_filePath` ON `events` (`filePath`)")
            }
        }

        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "memo.db",
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }
}
