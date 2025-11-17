package com.example.allrecorder

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
    suspend fun delete(recording: Recording)

    @Update
    suspend fun updateRecordings(recordings: List<Recording>)

    @Query("SELECT * FROM recordings ORDER BY startTime DESC")
    fun getAllRecordings(): Flow<List<Recording>>


    @Query("SELECT * FROM recordings WHERE processingStatus = 0 ORDER BY startTime ASC")
    suspend fun getUnprocessedRecordings(): List<Recording>
    // --- END OF FIX ---

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecording(id: Long): Recording?
}