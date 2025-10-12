package com.example.allrecorder

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Recording::class, Conversation::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create the new 'conversations' table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `conversations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER NOT NULL
                    )
                """.trimIndent())

                // Create a new 'recordings' table with the desired schema and foreign key
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `recordings_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `duration` INTEGER NOT NULL,
                        `transcript` TEXT,
                        `speakerLabels` TEXT,
                        `conversationId` INTEGER,
                        `isProcessed` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())

                // Copy data from the old table to the new one
                db.execSQL("""
                    INSERT INTO `recordings_new` (id, filePath, startTime, duration, transcript, speakerLabels)
                    SELECT id, filePath, startTime, duration, transcript, speakerLabels FROM `recordings`
                """.trimIndent())

                // Remove the old table
                db.execSQL("DROP TABLE `recordings`")

                // Rename the new table to the original table name
                db.execSQL("ALTER TABLE `recordings_new` RENAME TO `recordings`")

                // Create an index on the new foreign key column
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_conversationId` ON `recordings` (`conversationId`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "audio_recorder_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

