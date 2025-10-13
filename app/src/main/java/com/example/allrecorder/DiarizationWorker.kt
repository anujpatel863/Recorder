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
import org.tensorflow.lite.Interpreter
import kotlin.math.exp
import kotlin.math.sqrt

// Data class for a speech segment with its voiceprint
data class SpeechEvent(
    val startTimeSeconds: Float,
    val endTimeSeconds: Float,
    val speakerEmbedding: FloatArray
)

// Represents a unique speaker and all their speech segments
private data class SpeakerCluster(
    var representativeEmbedding: FloatArray,
    val events: MutableList<SpeechEvent> = mutableListOf()
)

// Carries context across audio chunks in a single timeline
private data class TimelineState(
    val timelineStartTime: Long,
    val knownSpeakers: MutableList<SpeakerCluster> = mutableListOf(),
    var activeConversationEvents: MutableList<SpeechEvent> = mutableListOf(),
    var cumulativeTimeOffset: Float = 0f
)

class DiarizationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val recordingDao = AppDatabase.getDatabase(appContext).recordingDao()
    private val conversationDao = AppDatabase.getDatabase(appContext).conversationDao()
    private val speakerInterpreter: Interpreter
    private val vadInterpreter: Interpreter

    companion object {
        const val WORK_NAME = "DiarizationWorker"
        private const val TAG = "DiarizationWorker"
        const val PROGRESS = "PROGRESS"
        private const val SAMPLE_RATE = 16000f
        private const val VAD_INPUT_SIZE = 528
        private const val CHUNK_CONTINUITY_THRESHOLD_MILLIS = 5000 // 5 seconds
        private const val CONVERSATION_BREAK_THRESHOLD_SECONDS = 10 // A 10-second silence indicates a new conversation
        private const val SIMILARITY_THRESHOLD = 0.85 // How similar two voiceprints must be to be the same person
    }

    init {
        SettingsManager.init(appContext)
        speakerInterpreter = Interpreter(loadModelFile(SettingsManager.selectedDiarizationModel))
        vadInterpreter = Interpreter(loadModelFile("vad_long_model.tflite"))
    }

    override suspend fun doWork(): Result {
        try {
            val unprocessedRecordings = recordingDao.getUnprocessedRecordings().sortedBy { it.startTime }
            if (unprocessedRecordings.isEmpty()) return Result.success()

            val totalRecordings = unprocessedRecordings.size
            var recordingsProcessedCount = 0
            var currentTimeline = mutableListOf<Recording>()
            var lastRecordingEndTime: Long = 0

            for (recording in unprocessedRecordings) {
                if (currentTimeline.isNotEmpty()) {
                    val timeDiff = recording.startTime - lastRecordingEndTime
                    if (timeDiff > CHUNK_CONTINUITY_THRESHOLD_MILLIS) {
                        processTimeline(currentTimeline)
                        recordingsProcessedCount += currentTimeline.size
                        updateProgress(recordingsProcessedCount, totalRecordings)
                        currentTimeline = mutableListOf()
                    }
                }
                currentTimeline.add(recording)
                lastRecordingEndTime = recording.startTime + recording.duration
            }

            if (currentTimeline.isNotEmpty()) {
                processTimeline(currentTimeline)
                recordingsProcessedCount += currentTimeline.size
                updateProgress(recordingsProcessedCount, totalRecordings)
            }

            setProgress(createProgressData(DiarizationProgress(100, "Processing complete!", totalRecordings, totalRecordings)))
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Diarization worker failed catastrophically", e)
            return Result.failure()
        }
    }

    private suspend fun processTimeline(timeline: List<Recording>) {
        if (timeline.isEmpty()) return
        val state = TimelineState(timeline.first().startTime)

        for (recording in timeline) {
            val chunkAudioData = readAudioFile(recording.filePath)
            if (chunkAudioData.isEmpty()) {
                state.cumulativeTimeOffset += (recording.duration / 1000f)
                continue
            }

            val chunkEvents = findSpeechEventsInChunk(chunkAudioData)
            processChunkEvents(chunkEvents, state)

            state.cumulativeTimeOffset += (chunkAudioData.size / SAMPLE_RATE)
        }

        if (state.activeConversationEvents.isNotEmpty()) {
            finalizeConversation(state)
        }

        val processedRecordings = timeline.map { it.copy(isProcessed = true) }
        recordingDao.updateRecordings(processedRecordings)
    }

    // --- START OF FIX ---
    // This function must be marked as 'suspend' because it calls finalizeConversation (a suspend function).
    private suspend fun processChunkEvents(chunkEvents: List<SpeechEvent>, state: TimelineState) {
        // --- END OF FIX ---
        for (event in chunkEvents) {
            val adjustedEvent = event.copy(
                startTimeSeconds = event.startTimeSeconds + state.cumulativeTimeOffset,
                endTimeSeconds = event.endTimeSeconds + state.cumulativeTimeOffset
            )

            val bestMatch = findBestSpeakerCluster(adjustedEvent, state.knownSpeakers)
            if (bestMatch == null) {
                state.knownSpeakers.add(SpeakerCluster(adjustedEvent.speakerEmbedding, mutableListOf(adjustedEvent)))
            } else {
                bestMatch.events.add(adjustedEvent)
            }

            val lastEvent = state.activeConversationEvents.lastOrNull()
            if (lastEvent != null && (adjustedEvent.startTimeSeconds - lastEvent.endTimeSeconds) > CONVERSATION_BREAK_THRESHOLD_SECONDS) {
                finalizeConversation(state)
                state.activeConversationEvents.add(adjustedEvent)
            } else {
                state.activeConversationEvents.add(adjustedEvent)
            }
        }
    }

    private fun findBestSpeakerCluster(event: SpeechEvent, clusters: List<SpeakerCluster>): SpeakerCluster? {
        if (clusters.isEmpty()) return null
        return clusters.maxByOrNull { cosineSimilarity(it.representativeEmbedding, event.speakerEmbedding) }
            ?.takeIf { cosineSimilarity(it.representativeEmbedding, event.speakerEmbedding) > SIMILARITY_THRESHOLD }
    }

    private suspend fun finalizeConversation(state: TimelineState) {
        if (state.activeConversationEvents.isEmpty()) return

        val firstEvent = state.activeConversationEvents.first()
        val lastEvent = state.activeConversationEvents.last()

        val speakerEmbeddingsInConversation = state.activeConversationEvents.map { it.speakerEmbedding }
        val uniqueSpeakersInConversation = speakerEmbeddingsInConversation.distinctBy { embedding ->
            state.knownSpeakers.find { cluster -> cosineSimilarity(cluster.representativeEmbedding, embedding) > SIMILARITY_THRESHOLD }
        }.size

        val conversation = Conversation(
            startTime = state.timelineStartTime + (firstEvent.startTimeSeconds * 1000).toLong(),
            endTime = state.timelineStartTime + (lastEvent.endTimeSeconds * 1000).toLong(),
            speakerCount = uniqueSpeakersInConversation.coerceAtLeast(1), // Ensure at least 1 speaker
            title = "Conversation at ${formatDate(state.timelineStartTime + (firstEvent.startTimeSeconds * 1000).toLong())}"
        )
        conversationDao.insert(conversation)

        state.activeConversationEvents.clear()
    }

    private fun findSpeechEventsInChunk(audioData: FloatArray): List<SpeechEvent> {
        val events = mutableListOf<SpeechEvent>()
        if (audioData.isEmpty()) return events

        val analysisWindowSize = SAMPLE_RATE.toInt()
        val numWindows = audioData.size / analysisWindowSize
        var currentSpeechSegment = mutableListOf<Float>()
        var segmentStartTime = 0.0f

        for (i in 0 until numWindows) {
            val windowStart = i * analysisWindowSize
            val windowEnd = minOf(windowStart + analysisWindowSize, audioData.size)
            val audioWindow = audioData.sliceArray(windowStart until windowEnd)

            if (isSpeech(audioWindow)) {
                if (currentSpeechSegment.isEmpty()) {
                    segmentStartTime = windowStart / SAMPLE_RATE
                }
                currentSpeechSegment.addAll(audioWindow.toList())
            } else {
                if (currentSpeechSegment.isNotEmpty()) {
                    val segmentEndTime = windowStart / SAMPLE_RATE
                    val embedding = getSpeakerEmbedding(currentSpeechSegment.toFloatArray())
                    events.add(SpeechEvent(segmentStartTime, segmentEndTime, embedding))
                    currentSpeechSegment.clear()
                }
            }
        }

        if (currentSpeechSegment.isNotEmpty()) {
            val segmentEndTime = audioData.size / SAMPLE_RATE
            val embedding = getSpeakerEmbedding(currentSpeechSegment.toFloatArray())
            events.add(SpeechEvent(segmentStartTime, segmentEndTime, embedding))
        }
        return events
    }

    private fun isSpeech(audioWindow: FloatArray): Boolean {
        val inputBuffer = ByteBuffer.allocateDirect(VAD_INPUT_SIZE * 4).order(ByteOrder.nativeOrder())
        for(i in 0 until VAD_INPUT_SIZE) {
            inputBuffer.putFloat(if(i < audioWindow.size) audioWindow[i] else 0.0f)
        }
        inputBuffer.rewind()
        val outputArray = Array(1) { FloatArray(4) }
        try {
            vadInterpreter.run(inputBuffer, outputArray)
        } catch (e: Exception) {
            return false
        }
        val logits = outputArray[0]
        val probabilities = logits.map { 1.0f / (1.0f + exp(-it)) }
        return (probabilities.maxOrNull() ?: 0f) > 0.5f
    }

    private suspend fun updateProgress(processedCount: Int, total: Int) {
        val progress = DiarizationProgress((processedCount * 100) / total, "Processing recording $processedCount of $total...", total, processedCount)
        setProgress(createProgressData(progress))
    }

    private fun getSpeakerEmbedding(audioSegment: FloatArray): FloatArray {
        val inputBuffer = ByteBuffer.allocateDirect(SAMPLE_RATE.toInt() * 4).order(ByteOrder.nativeOrder())
        val segmentPadded = audioSegment.copyOf(SAMPLE_RATE.toInt())
        for (sample in segmentPadded) {
            inputBuffer.putFloat(sample)
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
        val dotProduct = vec1.zip(vec2).sumOf { (a, b) -> (a * b).toDouble() }
        val normA = sqrt(vec1.sumOf { (it * it).toDouble() })
        val normB = sqrt(vec2.sumOf { (it * it).toDouble() })
        return if (normA == 0.0 || normB == 0.0) 0.0f else (dotProduct / (normA * normB)).toFloat()
    }

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("MMM dd, h:mm:ss a", Locale.getDefault()).format(Date(timestamp))

    private fun createProgressData(progress: DiarizationProgress): Data =
        workDataOf(PROGRESS to Gson().toJson(progress))

    private fun readAudioFile(filePath: String): FloatArray {
        val extractor = MediaExtractor()
        val audioSamples = mutableListOf<Float>()
        try {
            extractor.setDataSource(filePath)
            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex == -1) return FloatArray(0)
            extractor.selectTrack(trackIndex)
            val buffer = ByteBuffer.allocate(1024 * 8).order(ByteOrder.nativeOrder())
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize <= 0) break
                buffer.rewind()
                while (buffer.remaining() >= 2) {
                    audioSamples.add(buffer.getShort().toFloat() / 32768.0f)
                }
                buffer.clear()
                extractor.advance()
            }
        } catch (e: Exception) {
            return FloatArray(0)
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
        applicationContext.assets.openFd(modelName).use { fileDescriptor ->
            FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                val channel = inputStream.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
            }
        }
    }
}