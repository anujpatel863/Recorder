package com.example.allrecorder

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

data class SpeakerEmbedding(val embedding: FloatArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SpeakerEmbedding
        return embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        return embedding.contentHashCode()
    }
}

data class DiarizationResult(
    val speakerId: Int,
    val segment: SpeechSegment,
    val audioChunk: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DiarizationResult

        if (speakerId != other.speakerId) return false
        if (segment != other.segment) return false
        if (!audioChunk.contentEquals(other.audioChunk)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = speakerId
        result = 31 * result + segment.hashCode()
        result = 31 * result + audioChunk.contentHashCode()
        return result
    }
}


class DiarizationService(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val modelPath = "conformer_tisid_small.tflite"

    // --- Clustering State ---
    private val speakerClusters = mutableMapOf<Int, SpeakerEmbedding>()
    private var nextSpeakerId = 1

    companion object {
        private const val TAG = "DiarizationService"
        private const val SAMPLE_RATE = 16000
        // This should match the model's expected input size.
        // Example: 1 second of audio. This needs to be verified from model documentation.
        private const val MODEL_INPUT_SAMPLES = 1 * SAMPLE_RATE
        private const val SIMILARITY_THRESHOLD = 0.75f // Cosine similarity threshold for clustering
    }

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile(modelPath)
            val options = Interpreter.Options()
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "Diarization model loaded successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading diarization model", e)
        }
    }

    private fun loadModelFile(modelPath: String): ByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun process(speechSegments: List<SpeechSegment>, fullAudio: FloatArray): List<DiarizationResult> {
        if (interpreter == null) {
            Log.e(TAG, "Diarization interpreter is not initialized.")
            return emptyList()
        }

        val results = mutableListOf<DiarizationResult>()

        for (segment in speechSegments) {
            val startSample = (segment.start / 1000 * SAMPLE_RATE).toLong().toInt()
            val endSample = (segment.end / 1000 * SAMPLE_RATE).toLong().toInt()
            val audioChunk = fullAudio.sliceArray(startSample..endSample.coerceAtMost(fullAudio.size - 1))

            if (audioChunk.isEmpty()) continue

            val embedding = extractEmbedding(audioChunk)
            if (embedding == null) {
                Log.w(TAG, "Could not extract embedding for segment: $segment")
                continue
            }

            val speakerId = findMatchingSpeaker(embedding)
            results.add(DiarizationResult(speakerId, segment, audioChunk))
        }

        Log.i(TAG, "Diarization complete. Identified ${speakerClusters.size} unique speakers.")
        return results
    }

    private fun extractEmbedding(audioChunk: FloatArray): SpeakerEmbedding? {
        // The model may expect a fixed size input. We take a chunk from the center.
        val startIndex = (audioChunk.size / 2) - (MODEL_INPUT_SAMPLES / 2)
        val endIndex = startIndex + MODEL_INPUT_SAMPLES
        val modelInput = if (startIndex < 0 || endIndex > audioChunk.size) {
            // Pad if the chunk is too short
            FloatArray(MODEL_INPUT_SAMPLES).apply {
                audioChunk.copyInto(this, 0, 0, audioChunk.size.coerceAtMost(MODEL_INPUT_SAMPLES))
            }
        } else {
            audioChunk.sliceArray(startIndex until endIndex)
        }

        val inputBuffer = ByteBuffer.allocateDirect(MODEL_INPUT_SAMPLES * 4).apply {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().put(modelInput)
            rewind()
        }

        // The output shape will be something like [1, embedding_size]
        val outputShape = interpreter!!.getOutputTensor(0).shape()
        val embeddingSize = outputShape[1]
        val outputBuffer = ByteBuffer.allocateDirect(embeddingSize * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        try {
            interpreter!!.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            val embedding = FloatArray(embeddingSize)
            outputBuffer.asFloatBuffer().get(embedding)
            return SpeakerEmbedding(embedding)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run embedding extraction", e)
            return null
        }
    }

    private fun findMatchingSpeaker(embedding: SpeakerEmbedding): Int {
        var bestMatchId = -1
        var bestSimilarity = -1.0f

        for ((speakerId, clusterEmbedding) in speakerClusters) {
            val similarity = cosineSimilarity(embedding.embedding, clusterEmbedding.embedding)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestMatchId = speakerId
            }
        }

        return if (bestSimilarity >= SIMILARITY_THRESHOLD) {
            // Found a match, update the cluster's average embedding (optional, can be improved)
            bestMatchId
        } else {
            // No match found, create a new speaker
            val newSpeakerId = nextSpeakerId++
            speakerClusters[newSpeakerId] = embedding
            newSpeakerId
        }
    }

    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        var dotProduct = 0.0f
        var norm1 = 0.0f
        var norm2 = 0.0f
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }
        return dotProduct / (sqrt(norm1) * sqrt(norm2))
    }

    fun reset() {
        speakerClusters.clear()
        nextSpeakerId = 1
        Log.d(TAG, "DiarizationService reset.")
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "DiarizationService closed.")
    }
}
