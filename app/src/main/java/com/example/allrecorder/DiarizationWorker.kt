package com.example.allrecorder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

class DiarizationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val recordingDao = AppDatabase.getDatabase(appContext).recordingDao()
    private val conversationDao = AppDatabase.getDatabase(appContext).conversationDao()
    private val speakerInterpreter: Interpreter

    companion object {
        const val WORK_NAME = "DiarizationWorker"
        private const val TAG = "DiarizationWorker"
        private const val SIMILARITY_THRESHOLD = 0.85 // Increased for better speaker grouping
        private const val SILENCE_EMBEDDING_THRESHOLD = 10.0 // Threshold for silence detection
        const val PROGRESS = "PROGRESS"
    }

    init {
        // We will only use the speaker identification model
        speakerInterpreter = Interpreter(loadModelFile("conformer_tisid_small.tflite"))
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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Diarization worker started.")
        try {
            val unprocessedRecordings = recordingDao.getUnprocessedRecordings()
            if (unprocessedRecordings.isEmpty()) {
                Log.d(TAG, "No new recordings to process.")
                return@withContext Result.success()
            }

            Log.d(TAG, "Processing ${unprocessedRecordings.size} recordings.")
            setProgress(workDataOf(PROGRESS to 0))

            // Step 1: Process all recordings to get embeddings or mark as silent
            val processedRecordings = unprocessedRecordings.mapIndexed { index, recording ->
                val embedding = getSpeakerEmbedding(recording.filePath)
                val isSilent = isSilent(embedding)
                val progress = ((index + 1) * 100) / unprocessedRecordings.size
                setProgress(workDataOf(PROGRESS to progress))

                if (isSilent) {
                    recording.copy(speakerLabels = "SILENCE", isProcessed = true)
                } else {
                    recording.copy(speakerLabels = embedding.joinToString(","), isProcessed = true)
                }
            }

            // Step 2: Group the processed recordings into conversations
            groupIntoConversations(processedRecordings)
            setProgress(workDataOf(PROGRESS to 100))
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Diarization worker failed", e)
            Result.failure()
        }
    }

    private fun isSilent(embedding: FloatArray): Boolean {
        // Calculate the magnitude of the embedding. Silent audio has a low magnitude.
        val norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        Log.d(TAG, "Calculated embedding norm: $norm")
        return norm < SILENCE_EMBEDDING_THRESHOLD
    }

    private fun getSpeakerEmbedding(filePath: String): FloatArray {
        // TODO: Replace this with actual audio preprocessing.
        // The model expects a 1-second audio clip at 16kHz.
        val inputBuffer = Array(1) { FloatArray(16000) }
        val outputBuffer = Array(1) { FloatArray(512) }
        try {
            speakerInterpreter.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite interpreter failed for file: $filePath", e)
        }
        return outputBuffer[0]
    }

    private suspend fun groupIntoConversations(recordings: List<Recording>) {
        val speechChunks = recordings.filter { it.speakerLabels != "SILENCE" }
        val silentChunks = recordings.filter { it.speakerLabels == "SILENCE" }
        recordingDao.updateRecordings(silentChunks) // Update silent chunks as processed

        var currentConversationChunks = mutableListOf<Recording>()
        var lastSpeakerEmbedding: FloatArray? = null

        for (chunk in speechChunks) {
            val currentEmbedding = chunk.speakerLabels!!.split(',').map { it.toFloat() }.toFloatArray()

            if (lastSpeakerEmbedding == null || cosineSimilarity(lastSpeakerEmbedding, currentEmbedding) > SIMILARITY_THRESHOLD) {
                // Same speaker or first speaker, add to current conversation
                currentConversationChunks.add(chunk)
            } else {
                // Different speaker, finalize the previous conversation
                finalizeConversation(currentConversationChunks)
                // Start a new conversation with the current chunk
                currentConversationChunks = mutableListOf(chunk)
            }
            lastSpeakerEmbedding = currentEmbedding
        }

        // Finalize any remaining conversation
        if (currentConversationChunks.isNotEmpty()) {
            finalizeConversation(currentConversationChunks)
        }
    }

    private suspend fun finalizeConversation(chunks: List<Recording>) {
        if (chunks.isEmpty()) return

        // Identify unique speakers in this conversation
        val uniqueSpeakers = mutableListOf<FloatArray>()
        chunks.forEach { chunk ->
            val embedding = chunk.speakerLabels!!.split(',').map { it.toFloat() }.toFloatArray()
            if (uniqueSpeakers.none { cosineSimilarity(it, embedding) > SIMILARITY_THRESHOLD }) {
                uniqueSpeakers.add(embedding)
            }
        }

        val firstChunk = chunks.first()
        val lastChunk = chunks.last()
        val conversation = Conversation(
            title = "Conversation at ${formatDate(firstChunk.startTime)}",
            startTime = firstChunk.startTime,
            endTime = lastChunk.startTime + lastChunk.duration,
            speakerCount = uniqueSpeakers.size
        )
        val conversationId = conversationDao.insert(conversation)
        chunks.forEach { it.conversationId = conversationId }
        recordingDao.updateRecordings(chunks)
    }

    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        val dotProduct = vec1.zip(vec2).sumOf { (a, b) -> (a * b).toDouble() }.toFloat()
        val normA = sqrt(vec1.sumOf { (it * it).toDouble() }).toFloat()
        val normB = sqrt(vec2.sumOf { (it * it).toDouble() }).toFloat()
        return if (normA == 0f || normB == 0f) 0f else dotProduct / (normA * normB)
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault()).format(Date(timestamp))
    }
}

