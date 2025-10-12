package com.example.allrecorder

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording)

    @Query("SELECT * FROM recordings ORDER BY startTime DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    @Update
    suspend fun update(recording: Recording)

    @Update
    suspend fun updateRecordings(recordings: List<Recording>) // To update chunks in batch

    @Delete
    suspend fun delete(recording: Recording)

    @Query("SELECT * FROM recordings WHERE isProcessed = 0 ORDER BY startTime ASC")
    suspend fun getUnprocessedRecordings(): List<Recording>

    @Query("SELECT * FROM recordings WHERE conversationId = :conversationId ORDER BY startTime ASC")
    fun getRecordingsForConversation(conversationId: Long): Flow<List<Recording>>
}
