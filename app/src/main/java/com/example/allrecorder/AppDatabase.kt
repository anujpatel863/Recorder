package com.example.allrecorder

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Add Conversation::class back to the entities array
@Database(entities = [Recording::class, Conversation::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao
    abstract fun conversationDao(): ConversationDao // Add this back

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "recorder_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}