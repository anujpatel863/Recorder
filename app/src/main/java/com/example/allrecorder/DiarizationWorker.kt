package com.example.allrecorder

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter

// (SpeechEvent data class remains the same)
data class SpeechEvent(
    val startTimeSeconds: Float,
    val endTimeSeconds: Float,
    val speakerEmbedding: FloatArray
)

class DiarizationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    // (Properties remain the same)
    private val recordingDao = AppDatabase.getDatabase(appContext).recordingDao()
    private val conversationDao = AppDatabase.getDatabase(appContext).conversationDao()
    private val speakerInterpreter: Interpreter
    private val vadInterpreter: Interpreter

    companion object {
        const val WORK_NAME = "DiarizationWorker"
        private const val TAG = "DiarizationWorker"
        const val PROGRESS = "PROGRESS"
        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_FLOAT = 4
        private const val VAD_INPUT_SIZE = 528
        private const val CHUNK_CONTINUITY_THRESHOLD_MILLIS = 2000
        private const val SIMILARITY_THRESHOLD = 0.85
        // New threshold to detect a definite speaker change
        private const val SPEAKER_CHANGE_THRESHOLD = 0.70
    }

    // (init, doWork, processTimeline, findAllSpeechEvents, and all utility functions remain the same)
    init {
        speakerInterpreter = Interpreter(loadModelFile("conformer_tisid_small.tflite"))
        vadInterpreter = Interpreter(loadModelFile("vad_long_model.tflite"))
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Get all unprocessed recordings and sort them chronologically
            val unprocessedRecordings = recordingDao.getUnprocessedRecordings().sortedBy { it.startTime }
            if (unprocessedRecordings.isEmpty()) return@withContext Result.success()

            val initialProgress = DiarizationProgress(0, "Starting analysis...", unprocessedRecordings.size, 0)
            setProgress(createProgressData(initialProgress))

            var timelineAudioData = mutableListOf<Float>()
            var timelineRecordings = mutableListOf<Recording>()
            var lastRecordingEndTime: Long = 0

            for ((index, recording) in unprocessedRecordings.withIndex()) {
                val progressUpdate = DiarizationProgress(
                    ((index + 1) * 100) / unprocessedRecordings.size,
                    "Processing recording ${index + 1}...",
                    unprocessedRecordings.size,
                    index + 1
                )
                setProgress(createProgressData(progressUpdate))

                if (timelineRecordings.isNotEmpty()) {
                    val timeDiff = recording.startTime - lastRecordingEndTime
                    if (timeDiff > CHUNK_CONTINUITY_THRESHOLD_MILLIS) {
                        // Gap detected, process the completed timeline
                        processTimeline(timelineAudioData.toFloatArray(), timelineRecordings)
                        // Start a new timeline
                        timelineAudioData = mutableListOf()
                        timelineRecordings = mutableListOf()
                    }
                }

                // Add current recording to the timeline
                timelineAudioData.addAll(readAudioFile(recording.filePath).toList())
                timelineRecordings.add(recording)
                lastRecordingEndTime = recording.startTime + recording.duration
            }

            // Process the final timeline after the loop
            if (timelineRecordings.isNotEmpty()) {
                processTimeline(timelineAudioData.toFloatArray(), timelineRecordings)
            }

            // Mark all processed recordings
            val processed = unprocessedRecordings.map { it.copy(isProcessed = true) }
            recordingDao.updateRecordings(processed)

            val finalProgress = DiarizationProgress(100, "Processing complete!", unprocessedRecordings.size, unprocessedRecordings.size)
            setProgress(createProgressData(finalProgress))

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Diarization worker failed", e)
            Result.failure()
        }
    }

    private suspend fun processTimeline(audioData: FloatArray, recordingsInTimeline: List<Recording>) {
        val allSpeechEvents = findAllSpeechEvents(audioData)
        groupEventsIntoConversations(allSpeechEvents, recordingsInTimeline)
    }

    private fun findAllSpeechEvents(audioData: FloatArray): List<SpeechEvent> {
        val events = mutableListOf<SpeechEvent>()
        if (audioData.isEmpty()) return events

        val analysisWindowSize = SAMPLE_RATE // 1 second
        val numWindows = audioData.size / analysisWindowSize

        var currentSpeechSegment = mutableListOf<Float>()
        var segmentStartTime = 0.0f

        for (i in 0 until numWindows) {
            val windowStart = i * analysisWindowSize
            val windowEnd = windowStart + analysisWindowSize
            val audioWindow = audioData.sliceArray(windowStart until windowEnd)

            if (isSpeech(audioWindow)) {
                if (currentSpeechSegment.isEmpty()) {
                    segmentStartTime = (windowStart.toFloat() / SAMPLE_RATE)
                }
                currentSpeechSegment.addAll(audioWindow.toList())
            } else {
                if (currentSpeechSegment.isNotEmpty()) {
                    val segmentEndTime = (windowStart.toFloat() / SAMPLE_RATE)
                    val embedding = getSpeakerEmbedding(currentSpeechSegment.toFloatArray())
                    events.add(SpeechEvent(segmentStartTime, segmentEndTime, embedding))
                    currentSpeechSegment.clear()
                }
            }
        }

        if (currentSpeechSegment.isNotEmpty()) {
            val segmentEndTime = (audioData.size.toFloat() / SAMPLE_RATE)
            val embedding = getSpeakerEmbedding(currentSpeechSegment.toFloatArray())
            events.add(SpeechEvent(segmentStartTime, segmentEndTime, embedding))
        }
        return events
    }


    /**
     * This is the upgraded core logic.
     */
    private suspend fun groupEventsIntoConversations(events: List<SpeechEvent>, recordingsInTimeline: List<Recording>) {
        if (events.isEmpty()) return

        var currentConversationEvents = mutableListOf<SpeechEvent>()
        val timelineStartTime = recordingsInTimeline.first().startTime

        for (event in events) {
            if (currentConversationEvents.isEmpty()) {
                currentConversationEvents.add(event)
                continue
            }

            val lastEvent = currentConversationEvents.last()
            val silenceDuration = event.startTimeSeconds - lastEvent.endTimeSeconds

            var splitConversation = false

            // Rule 1: Long silence always splits the conversation
            if (silenceDuration >= SettingsManager.silenceThresholdSeconds) {
                splitConversation = true
            }

            // Rule 2: If smart detection is on, a speaker change also splits the conversation
            if (!splitConversation && SettingsManager.isSmartDetectionEnabled) {
                val similarity = cosineSimilarity(lastEvent.speakerEmbedding, event.speakerEmbedding)
                if (similarity < SPEAKER_CHANGE_THRESHOLD) {
                    splitConversation = true
                }
            }

            if (splitConversation) {
                // Finalize the previous conversation
                finalizeConversation(currentConversationEvents, timelineStartTime)
                // Start a new conversation
                currentConversationEvents = mutableListOf(event)
            } else {
                // It's the same conversation, just add the event
                currentConversationEvents.add(event)
            }
        }

        // Finalize any remaining conversation
        if (currentConversationEvents.isNotEmpty()) {
            finalizeConversation(currentConversationEvents, timelineStartTime)
        }
    }

    private suspend fun finalizeConversation(events: List<SpeechEvent>, timelineStartTime: Long) {
        if (events.isEmpty()) return

        val firstEvent = events.first()
        val lastEvent = events.last()

        val uniqueSpeakers = mutableListOf<FloatArray>()
        events.forEach { event ->
            if (uniqueSpeakers.none { cosineSimilarity(it, event.speakerEmbedding) > SIMILARITY_THRESHOLD }) {
                uniqueSpeakers.add(event.speakerEmbedding)
            }
        }

        // The file path isn't relevant anymore as a conversation can span multiple files.
        // For playback, we would need to load multiple files and seek.
        // For now, let's simplify and remove the direct file path dependency from the Conversation object.
        val conversation = Conversation(
            startTime = timelineStartTime + (firstEvent.startTimeSeconds * 1000).toLong(),
            endTime = timelineStartTime + (lastEvent.endTimeSeconds * 1000).toLong(),
            speakerCount = uniqueSpeakers.size,
            title = "Conversation at ${formatDate(timelineStartTime + (firstEvent.startTimeSeconds * 1000).toLong())}"
        )
        conversationDao.insert(conversation)
    }

    // --- Utility Functions (unchanged from previous correct versions) ---

    private fun isSpeech(audioWindow: FloatArray): Boolean {
        val inputBuffer = ByteBuffer.allocateDirect(VAD_INPUT_SIZE * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder())
        val outputBuffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        for(i in 0 until VAD_INPUT_SIZE) {
            inputBuffer.putFloat(if(i < audioWindow.size) audioWindow[i] else 0.0f)
        }
        inputBuffer.rewind()
        outputBuffer.rewind()
        vadInterpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()
        val probabilities = FloatArray(4)
        outputBuffer.asFloatBuffer().get(probabilities)
        return (probabilities.maxOrNull() ?: 0f) > 0.5f
    }

    private fun getSpeakerEmbedding(audioSegment: FloatArray): FloatArray {
        val inputBuffer = ByteBuffer.allocateDirect(SAMPLE_RATE * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder())
        for (i in 0 until SAMPLE_RATE) {
            if (i < audioSegment.size) inputBuffer.putFloat(audioSegment[i]) else inputBuffer.putFloat(0.0f)
        }
        inputBuffer.rewind()
        val outputBuffer = Array(1) { FloatArray(512) }
        try {
            speakerInterpreter.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite speaker interpreter failed", e)
        }
        return outputBuffer[0]
    }

    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        val dotProduct = vec1.zip(vec2).sumOf { (a, b) -> (a * b).toDouble() }.toFloat()
        val normA = sqrt(vec1.sumOf { (it * it).toDouble() }).toFloat()
        val normB = sqrt(vec2.sumOf { (it * it).toDouble() }).toFloat()
        return if (normA == 0f || normB == 0f) 0f else dotProduct / (normA * normB)
    }

    private fun formatDate(timestamp: Long): String = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault()).format(Date(timestamp))

    private fun createProgressData(progress: DiarizationProgress): Data = workDataOf(PROGRESS to Gson().toJson(progress))

    private fun readAudioFile(filePath: String): FloatArray {
        val extractor = MediaExtractor()
        val audioSamples = mutableListOf<Float>()
        try {
            extractor.setDataSource(filePath)
            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex == -1) {
                Log.e(TAG, "No audio track found in file: $filePath")
                return FloatArray(0)
            }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val buffer = ByteBuffer.allocate(SAMPLE_RATE * 2).order(ByteOrder.nativeOrder())

            while (extractor.readSampleData(buffer, 0) >= 0) {
                buffer.rewind()
                // CRITICAL FIX: Check if there are at least 2 bytes remaining before reading a short
                while (buffer.remaining() >= 2) {
                    val sample = buffer.getShort().toFloat() / 32768.0f
                    audioSamples.add(sample)
                }
                buffer.clear()
                extractor.advance()
            }

            if (sampleRate != SAMPLE_RATE) {
                Log.w(TAG, "Sample rate mismatch for file: $filePath")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read audio file: $filePath", e)
            return FloatArray(0) // Return empty array on any error
        } finally {
            extractor.release()
        }
        return audioSamples.toFloatArray()
    }


    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        return -1
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = applicationContext.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

}