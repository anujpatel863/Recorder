package com.example.allrecorder

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording) // ADD THIS FUNCTION

    @Update
    suspend fun updateRecordings(recordings: List<Recording>)

    @Query("SELECT * FROM recordings ORDER BY startTime DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE isProcessed = 0 ORDER BY startTime ASC")
    suspend fun getUnprocessedRecordings(): List<Recording>

    // --- ADD THIS FUNCTION ---
    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecording(id: Long): Recording?

    @Query("SELECT * FROM recordings WHERE conversationId = :conversationId ORDER BY startTime ASC")
    fun getRecordingsForConversation(conversationId: Long): Flow<List<Recording>>

    // --- END OF FUNCTION TO ADD ---
    @Query("SELECT * FROM recordings WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getRecordingByConversationId(conversationId: Long): Recording?
}