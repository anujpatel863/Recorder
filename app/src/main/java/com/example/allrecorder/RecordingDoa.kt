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

    @Query("SELECT * FROM recordings WHERE isStarred = 1 ORDER BY startTime DESC")
    fun getStarredRecordings(): Flow<List<Recording>>
    @Query("SELECT * FROM recordings WHERE processingStatus = 0 ORDER BY startTime ASC")
    suspend fun getUnprocessedRecordings(): List<Recording>
    // --- END OF FIX ---

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecording(id: Long): Recording?
    // Add to RecordingDoa.kt
    @Query("SELECT * FROM recordings")
    suspend fun getAllRecordingsForSearch(): List<Recording>
    @Query("SELECT * FROM recordings WHERE embedding IS NULL AND transcript IS NOT NULL AND LENGTH(transcript) > 0")
    suspend fun getRecordingsMissingEmbeddings(): List<Recording>

    @Query("SELECT * FROM recordings WHERE startTime < :cutoffTimestamp AND isStarred = 0")
    suspend fun getOldNonStarredRecordings(cutoffTimestamp: Long): List<Recording>

    @Query("SELECT filePath FROM recordings")
    suspend fun getAllPaths(): List<String>
}