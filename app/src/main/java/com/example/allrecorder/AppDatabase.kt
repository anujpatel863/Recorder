package com.example.allrecorder

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

// Conversation::class REMOVED from entities
@Database(entities = [Recording::class], version = 6, exportSchema = false) // Version incremented
@TypeConverters(Converters::class) // Add this line
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao
    // abstract fun conversationDao(): ConversationDao // REMOVED

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
                    .fallbackToDestructiveMigration(dropAllTables = true)// This will handle the schema change
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}