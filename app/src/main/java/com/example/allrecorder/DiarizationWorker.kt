package com.example.allrecorder

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

class DiarizationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val recordingDao = AppDatabase.getDatabase(appContext).recordingDao()
    private val conversationDao = AppDatabase.getDatabase(appContext).conversationDao()

    companion object {
        const val WORK_NAME = "DiarizationWorker"
        private const val TAG = "DiarizationWorker"
        // Amplitude threshold to decide if a chunk is "silent". This needs tuning.
        private const val SILENCE_THRESHOLD = 300
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Worker started.")
        try {
            val unprocessedRecordings = recordingDao.getUnprocessedRecordings()
            if (unprocessedRecordings.isEmpty()) {
                Log.d(TAG, "No new recordings to process.")
                return@withContext Result.success()
            }

            Log.d(TAG, "Processing ${unprocessedRecordings.size} recordings.")

            val processedRecordings = unprocessedRecordings.map { recording ->
                val hasSpeech = analyzeRecordingForSpeech(recording.filePath)
                val speakerLabel = if (hasSpeech) "SPEECH_DETECTED" else "SILENCE"
                recording.copy(speakerLabels = speakerLabel, isProcessed = true)
            }

            groupIntoConversations(processedRecordings)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            Result.failure()
        }
    }

    private suspend fun groupIntoConversations(processedRecordings: List<Recording>) {
        var currentConversation: Conversation? = null
        val recordingsInCurrentConversation = mutableListOf<Recording>()

        for (recording in processedRecordings) {
            val isSilent = recording.speakerLabels == "SILENCE"

            if (!isSilent) {
                // This chunk contains speech
                if (currentConversation == null) {
                    // Start of a new conversation
                    val title = "Conversation from ${formatDate(recording.startTime)}"
                    val newConversation = Conversation(
                        title = title,
                        startTime = recording.startTime,
                        endTime = recording.startTime + recording.duration
                    )
                    val newConversationId = conversationDao.insert(newConversation)
                    currentConversation = newConversation.copy(id = newConversationId)
                }

                // Add recording to the current conversation
                recording.conversationId = currentConversation.id
                recordingsInCurrentConversation.add(recording)

                // Update conversation end time
                currentConversation.endTime = recording.startTime + recording.duration

            } else {
                // This chunk is silent, so the conversation ends
                if (currentConversation != null) {
                    conversationDao.update(currentConversation)
                    recordingDao.updateRecordings(recordingsInCurrentConversation)
                    currentConversation = null
                    recordingsInCurrentConversation.clear()
                }
                // Also update the silent recording as processed
                recordingDao.update(recording)
            }
        }

        // Save the last conversation if it exists
        if (currentConversation != null) {
            conversationDao.update(currentConversation)
            recordingDao.updateRecordings(recordingsInCurrentConversation)
        }
    }

    /**
     * Placeholder for a real diarization model.
     * This implementation uses a simple amplitude check to detect silence.
     * Returns true if speech is detected, false otherwise.
     */
    private fun analyzeRecordingForSpeech(filePath: String): Boolean {
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(filePath)

            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) return false
            extractor.selectTrack(trackIndex)

            val buffer = ByteBuffer.allocate(1024 * 16)
            var totalAmplitude: Long = 0
            var sampleCount: Long = 0

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                for (i in 0 until sampleSize step 2) { // Assuming 16-bit audio (2 bytes per sample)
                    if (i + 1 < sampleSize) {
                        val sample = buffer.getShort(i)
                        totalAmplitude += Math.abs(sample.toInt())
                        sampleCount++
                    }
                }
                extractor.advance()
            }

            if (sampleCount == 0L) return false
            val averageAmplitude = totalAmplitude / sampleCount
            Log.d(TAG, "File: $filePath, Avg Amplitude: $averageAmplitude")
            return averageAmplitude > SILENCE_THRESHOLD

        } catch (e: IOException) {
            Log.e(TAG, "Failed to analyze file: $filePath", e)
            return false // Treat errors as silence to be safe
        } finally {
            extractor?.release()
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format: MediaFormat = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }

    private fun formatDate(timestamp: Long): String {
        val date = Date(timestamp)
        val format = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault())
        return format.format(date)
    }
}
