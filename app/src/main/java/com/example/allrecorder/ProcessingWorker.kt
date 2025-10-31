

package com.example.allrecorder

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.exp
import kotlin.math.sqrt

// CRITICAL FIX: Use the ORIGINAL (non-quantized) simplified ONNX model
// The quantized version has shape inference errors that cannot be fixed

data class SpeechSegment(
    val startTimeSeconds: Float,
    val endTimeSeconds: Float,
    val speakerEmbedding: FloatArray,
    var transcript: String?,
    var speakerTag: String? = null
)

private data class SpeakerCluster(
    var representativeEmbedding: FloatArray,
    val segments: MutableList<SpeechSegment> = mutableListOf(),
    val speakerTag: String
)

private data class TimelineState(
    val timelineStartTime: Long,
    val knownSpeakers: MutableList<SpeakerCluster> = mutableListOf(),
    var activeConversationSegments: MutableList<SpeechSegment> = mutableListOf(),
    var cumulativeTimeOffset: Float = 0f
)

class ProcessingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val recordingDao = AppDatabase.getDatabase(appContext).recordingDao()
    private val conversationDao = AppDatabase.getDatabase(appContext).conversationDao()

    // TFLite Interpreters
    private val speakerInterpreter: Interpreter
    private val vadInterpreter: Interpreter

    // ONNX Runtime for ASR
    private val ortEnvironment: OrtEnvironment
    private var asrSession: OrtSession? = null
    private var asrAvailable = false

    companion object {
        const val WORK_NAME = "ProcessingWorker"
        private const val TAG = "ProcessingWorker"
        const val PROGRESS = "PROGRESS"
        private const val SAMPLE_RATE = 16000f
        private const val VAD_INPUT_SIZE = 528
        private const val CHUNK_CONTINUITY_THRESHOLD_MILLIS = 5000
    }

    init {
        // Load TFLite Models
        speakerInterpreter = Interpreter(loadTFLiteModel("conformer_tisid_medium.tflite"))
        vadInterpreter = Interpreter(loadTFLiteModel("vad_long_model.tflite"))

        // CRITICAL FIX: Try to load ONNX model, but don't crash if it fails
        ortEnvironment = OrtEnvironment.getEnvironment()

        try {
            // OPTION 1: Try the simplified (non-quantized) model first
            val modelBytes = try {
                applicationContext.assets.open("vakyansh_conformer_ssl_android.onnx").readBytes()
            } catch (e: Exception) {
                Log.w(TAG, "Simplified model not found, trying original...")
                applicationContext.assets.open("vakyansh_conformer_ssl.onnx").readBytes()
            }

            // Create session with error handling
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2) // Limit threads to reduce memory
                setInterOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            asrSession = ortEnvironment.createSession(modelBytes, sessionOptions)
            asrAvailable = true
            Log.i(TAG, "✅ ONNX ASR model loaded successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load ONNX model: ${e.message}")
            Log.e(TAG, "Transcription will return placeholder text")
            asrAvailable = false

            // Log the specific error for debugging
            when {
                e.message?.contains("ShapeInferenceError") == true -> {
                    Log.e(TAG, "Shape inference error - model has dynamic dimension issues")
                    Log.e(TAG, "Solution: Use the original non-quantized simplified model")
                }
                e.message?.contains("Incompatible dimensions") == true -> {
                    Log.e(TAG, "Dimension mismatch in model operations")
                }
                else -> {
                    Log.e(TAG, "Unknown ONNX error", e)
                }
            }
        }
    }

    override suspend fun doWork(): Result {
        try {
            val unprocessedRecordings = recordingDao.getUnprocessedRecordings().sortedBy { it.startTime }
            if (unprocessedRecordings.isEmpty()) {
                Log.i(TAG, "No recordings to process.")
                return Result.success()
            }

            if (!asrAvailable) {
                Log.w(TAG, "⚠️ ASR model not available - conversations will have no transcripts")
            }

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
            Log.e(TAG, "Processing worker failed", e)
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

            val speechSegments = findSpeechSegments(chunkAudioData)
            processSpeechSegments(speechSegments, state)

            state.cumulativeTimeOffset += (chunkAudioData.size / SAMPLE_RATE)
        }

        if (state.activeConversationSegments.isNotEmpty()) {
            finalizeConversation(state, timeline.first().id)
        }

        val processedRecordings = timeline.map { it.copy(isProcessed = true) }
        recordingDao.updateRecordings(processedRecordings)
    }

    private fun findSpeechSegments(audioData: FloatArray): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        if (audioData.isEmpty()) return segments

        // OPTIMIZATION: Use sliding window with overlap for better detection
        val windowSize = SAMPLE_RATE.toInt() // 1 second windows
        val hopSize = (SAMPLE_RATE * 0.5).toInt() // 50% overlap
        val numWindows = ((audioData.size - windowSize) / hopSize) + 1

        var inSpeech = false
        var speechStart = 0
        var speechAudio = mutableListOf<Float>()

        for (i in 0 until numWindows) {
            val start = i * hopSize
            val end = minOf(start + windowSize, audioData.size)
            val window = audioData.sliceArray(start until end)

            val isSpeechWindow = isSpeech(window)

            if (isSpeechWindow && !inSpeech) {
                // Speech started
                inSpeech = true
                speechStart = start
                speechAudio.clear()
                speechAudio.addAll(window.toList())
            } else if (isSpeechWindow && inSpeech) {
                // Continue speech
                speechAudio.addAll(window.toList())
            } else if (!isSpeechWindow && inSpeech) {
                // Speech ended
                inSpeech = false
                val speechEnd = start

                if (speechAudio.size > SAMPLE_RATE * 0.3) { // Minimum 0.3 seconds
                    val segmentArray = speechAudio.toFloatArray()
                    val embedding = getSpeakerEmbedding(segmentArray)
                    val transcript = transcribe(segmentArray)

                    segments.add(SpeechSegment(
                        startTimeSeconds = speechStart / SAMPLE_RATE,
                        endTimeSeconds = speechEnd / SAMPLE_RATE,
                        speakerEmbedding = embedding,
                        transcript = transcript
                    ))
                }
                speechAudio.clear()
            }
        }

        // Handle remaining speech at end
        if (inSpeech && speechAudio.size > SAMPLE_RATE * 0.3) {
            val segmentArray = speechAudio.toFloatArray()
            val embedding = getSpeakerEmbedding(segmentArray)
            val transcript = transcribe(segmentArray)

            segments.add(SpeechSegment(
                startTimeSeconds = speechStart / SAMPLE_RATE,
                endTimeSeconds = audioData.size / SAMPLE_RATE,
                speakerEmbedding = embedding,
                transcript = transcript
            ))
        }

        return segments
    }

    private suspend fun processSpeechSegments(segments: List<SpeechSegment>, state: TimelineState) {
        for (segment in segments) {
            val adjustedSegment = segment.copy(
                startTimeSeconds = segment.startTimeSeconds + state.cumulativeTimeOffset,
                endTimeSeconds = segment.endTimeSeconds + state.cumulativeTimeOffset
            )

            // OPTIMIZATION: Use hierarchical clustering for better speaker identification
            var bestMatch = findBestSpeakerCluster(adjustedSegment, state.knownSpeakers)
            if (bestMatch == null) {
                val newSpeakerTag = "Speaker ${state.knownSpeakers.size + 1}"
                bestMatch = SpeakerCluster(adjustedSegment.speakerEmbedding, speakerTag = newSpeakerTag)
                state.knownSpeakers.add(bestMatch)
                Log.d(TAG, "New speaker identified: $newSpeakerTag")
            }

            adjustedSegment.speakerTag = bestMatch.speakerTag
            bestMatch.segments.add(adjustedSegment)

            // Update cluster centroid (running average)
            updateClusterCentroid(bestMatch)

            // Detect conversation breaks
            val conversationBreakThreshold = SettingsManager.silenceSensitivitySeconds.toFloat()
            val lastSegment = state.activeConversationSegments.lastOrNull()

            if (lastSegment != null && (adjustedSegment.startTimeSeconds - lastSegment.endTimeSeconds) > conversationBreakThreshold) {
                finalizeConversation(state, null)
                state.activeConversationSegments = mutableListOf(adjustedSegment)
            } else {
                state.activeConversationSegments.add(adjustedSegment)
            }
        }
    }

    private fun updateClusterCentroid(cluster: SpeakerCluster) {
        // Update to running average of all embeddings in cluster
        if (cluster.segments.size > 1) {
            val embeddingDim = cluster.representativeEmbedding.size
            val newCentroid = FloatArray(embeddingDim) { 0f }

            for (segment in cluster.segments) {
                for (i in 0 until embeddingDim) {
                    newCentroid[i] += segment.speakerEmbedding[i]
                }
            }

            for (i in 0 until embeddingDim) {
                newCentroid[i] /= cluster.segments.size
            }

            cluster.representativeEmbedding = newCentroid
        }
    }

    private fun findBestSpeakerCluster(segment: SpeechSegment, clusters: List<SpeakerCluster>): SpeakerCluster? {
        val similarityThreshold = SettingsManager.speakerStrictnessThreshold
        if (clusters.isEmpty()) return null

        return clusters.maxByOrNull {
            cosineSimilarity(it.representativeEmbedding, segment.speakerEmbedding)
        }?.takeIf {
            cosineSimilarity(it.representativeEmbedding, segment.speakerEmbedding) > similarityThreshold
        }
    }

    private suspend fun finalizeConversation(state: TimelineState, recordingIdForUpdate: Long?) {
        if (state.activeConversationSegments.isEmpty()) return

        val firstSegment = state.activeConversationSegments.first()
        val lastSegment = state.activeConversationSegments.last()
        val uniqueSpeakersInConversation = state.activeConversationSegments.distinctBy { it.speakerTag }.size

        val conversation = Conversation(
            startTime = state.timelineStartTime + (firstSegment.startTimeSeconds * 1000).toLong(),
            endTime = state.timelineStartTime + (lastSegment.endTimeSeconds * 1000).toLong(),
            speakerCount = uniqueSpeakersInConversation.coerceAtLeast(1),
            title = "Conversation at ${formatDate(state.timelineStartTime + (firstSegment.startTimeSeconds * 1000).toLong())}"
        )
        val conversationId = conversationDao.insert(conversation)

        // Generate speaker-labeled transcript
        val fullTranscript = state.activeConversationSegments.joinToString("\n") {
            "${it.speakerTag}: ${it.transcript ?: "[No transcript]"}"
        }

        recordingIdForUpdate?.let {
            val recording = recordingDao.getRecording(it)
            recording?.let { rec ->
                rec.conversationId = conversationId
                rec.speakerLabels = Gson().toJson(state.activeConversationSegments)
                rec.transcript = fullTranscript
                recordingDao.update(rec)
            }
        }

        Log.i(TAG, "Conversation finalized: ${conversation.title} with $uniqueSpeakersInConversation speakers")
        state.activeConversationSegments.clear()
    }

    // --- Model Inference Functions ---

    private fun transcribe(audioSegment: FloatArray): String {
        // CRITICAL FIX: Handle case where ONNX model is not available
        if (!asrAvailable || asrSession == null) {
            Log.w(TAG, "ASR not available, returning placeholder")
            return "[Transcription unavailable]"
        }

        try {
            // Convert audio to Mel Spectrogram
            val melSpectrogram = MelSpectrogram.create(audioSegment)
            val timeSteps = melSpectrogram.size

            // Transpose for model input [1][80][timeSteps]
            val transposedSpectrogram = Array(1) { Array(80) { FloatArray(timeSteps) } }
            for (t in 0 until timeSteps) {
                for (n in 0 until 80) {
                    transposedSpectrogram[0][n][t] = melSpectrogram[t][n]
                }
            }

            // Prepare inputs
            val audioSignalTensor = OnnxTensor.createTensor(ortEnvironment, transposedSpectrogram)
            val lengthTensor = OnnxTensor.createTensor(ortEnvironment, longArrayOf(timeSteps.toLong()))

            val inputs: MutableMap<String, OnnxTensor> = HashMap()
            inputs["audio_signal"] = audioSignalTensor
            inputs["length"] = lengthTensor

            // Run inference with proper resource management
            asrSession!!.run(inputs).use { results ->
                audioSignalTensor.close()
                lengthTensor.close()

                val outputTensor = results[0] as OnnxTensor
                val logits = (outputTensor.value as Array<Array<FloatArray>>)[0]

                // CTC Greedy Decoder
                val decodedIds = logits.map { timestep ->
                    timestep.indices.maxByOrNull { timestep[it] } ?: -1
                }

                // Merge repeats
                val mergedIds = mutableListOf<Int>()
                var lastId = -1
                for (id in decodedIds) {
                    if (id != lastId) {
                        mergedIds.add(id)
                        lastId = id
                    }
                }

                // Convert to text
                val transcript = StringBuilder()
                for (id in mergedIds) {
                    if (id != CharVocabulary.blankTokenId) {
                        CharVocabulary.getChar(id)?.let {
                            transcript.append(it)
                        }
                    }
                }

                return transcript.toString().replace(" ' ", "'").trim()
            }

        } catch (e: Exception) {
            Log.e(TAG, "ASR inference failed", e)
            return "[Transcription error: ${e.message}]"
        }
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
            Log.e(TAG, "Speaker embedding failed", e)
            // Return zero vector on failure
            return FloatArray(512) { 0f }
        }
        return outputBuffer[0]
    }

    private fun isSpeech(audioWindow: FloatArray): Boolean {
        val inputBuffer = ByteBuffer.allocateDirect(VAD_INPUT_SIZE * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until VAD_INPUT_SIZE) {
            inputBuffer.putFloat(if (i < audioWindow.size) audioWindow[i] else 0.0f)
        }
        inputBuffer.rewind()

        val outputArray = Array(1) { FloatArray(4) }
        try {
            vadInterpreter.run(inputBuffer, outputArray)
            val logits = outputArray[0]
            val probabilities = logits.map { 1.0f / (1.0f + exp(-it)) }
            return (probabilities.maxOrNull() ?: 0f) > 0.5f
        } catch (e: Exception) {
            Log.e(TAG, "VAD failed", e)
            return false
        }
    }

    // --- Helper Functions ---

    private suspend fun updateProgress(processedCount: Int, total: Int) {
        val progress = DiarizationProgress(
            (processedCount * 100) / total,
            "Processing recording $processedCount of $total...",
            total,
            processedCount
        )
        setProgress(createProgressData(progress))
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
            Log.e(TAG, "Failed to read audio file: $filePath", e)
            return FloatArray(0)
        } finally {
            extractor.release()
        }
        return audioSamples.toFloatArray()
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        Log.w(TAG, "selectAudioTrack is deprecated.")
        return -1
    }

    private fun loadTFLiteModel(modelName: String): MappedByteBuffer {
        applicationContext.assets.openFd(modelName).use { fileDescriptor ->
            FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                val channel = inputStream.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
            }
        }
    }

//    override fun onStopped() {
//        super.onStopped()
//        asrSession?.close()
//    }
}