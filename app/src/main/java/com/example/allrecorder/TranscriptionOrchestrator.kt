package com.example.allrecorder

import android.app.Application
import android.util.Log
import com.example.allrecorder.models.ModelManager
import com.example.allrecorder.models.ModelRegistry
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FinalTranscriptSegment(
    val speakerId: Int,
    val start: Float,
    val end: Float,
    val text: String
)

class TranscriptionOrchestrator(
    private val application: Application,
    private val modelManager: ModelManager
) {

    private var offlineRecognizer: OfflineRecognizer? = null
    private var speakerDiarization: OfflineSpeakerDiarization? = null
    private var speechEnhancer: OfflineSpeechDenoiser? = null

    private var currentAsrLanguage: String = ""
    private var currentAsrModel: String = ""

    companion object {
        private const val TAG = "TranscriptionOrch"
        private const val SAMPLE_RATE = 16000
    }

    init {
        loadDiarizationModels()
    }

    private fun loadDiarizationModels() {
        try {
            val segmentationSpec = ModelRegistry.getSpec("segmentation")
            val embeddingSpec = ModelRegistry.getSpec("speaker_embed")

            if (modelManager.isModelReady(segmentationSpec) && modelManager.isModelReady(embeddingSpec)) {
                val segmentationPath = modelManager.getModelPath(segmentationSpec)
                val embeddingPath = modelManager.getModelPath(embeddingSpec)

                val pyannoteConfig = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationPath)
                val segmentationConfig = OfflineSpeakerSegmentationModelConfig(pyannote = pyannoteConfig, numThreads = 1, debug = false)
                val embeddingConfig = SpeakerEmbeddingExtractorConfig(model = embeddingPath, numThreads = 1, debug = false)
                val clusteringConfig = FastClusteringConfig(numClusters = -1, threshold = 0.5f)

                val diarizationConfig = OfflineSpeakerDiarizationConfig(
                    segmentation = segmentationConfig,
                    embedding = embeddingConfig,
                    clustering = clusteringConfig,
                    minDurationOn = 0.3f,
                    minDurationOff = 0.5f
                )

                speakerDiarization = OfflineSpeakerDiarization(config = diarizationConfig)
                Log.i(TAG, "Speaker Diarization loaded.")
            } else {
                Log.w(TAG, "Diarization models missing. Speaker ID will be skipped.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Diarization models.", e)
            speakerDiarization = null
        }
    }

    fun enhanceAudio(filePath: String): String? {
        if (speechEnhancer == null) {
            try {
                val gtcrnSpec = ModelRegistry.getSpec("gtcrn")
                if (!modelManager.isModelReady(gtcrnSpec)) return null

                val modelPath = modelManager.getModelPath(gtcrnSpec)
                val gtcrnConfig = OfflineSpeechDenoiserGtcrnModelConfig(model = modelPath)
                val modelConfig = OfflineSpeechDenoiserModelConfig(gtcrn = gtcrnConfig, numThreads = 1, debug = false, provider = "cpu")
                speechEnhancer = OfflineSpeechDenoiser(config = OfflineSpeechDenoiserConfig(model = modelConfig))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init enhancer", e)
                return null
            }
        }

        val audioSamples = readWavFile(filePath) ?: return null
        try {
            val denoiser = speechEnhancer ?: return null
            val denoisedAudio = denoiser.run(samples = audioSamples, sampleRate = SAMPLE_RATE)
            val tempFile = File.createTempFile("enhanced_", ".wav", application.cacheDir)
            denoisedAudio.save(filename = tempFile.absolutePath)
            return tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Enhancer failed", e)
            return null
        }
    }

    fun transcribe(
        filePath: String,
        language: String,
        modelName: String,
        onProgress: (Float) -> Unit
    ): List<FinalTranscriptSegment> {
        if (modelName != currentAsrModel || language != currentAsrLanguage || offlineRecognizer == null) {
            offlineRecognizer?.release()
            try {
                val encoderSpec = ModelRegistry.getSpec("${modelName}_encoder")
                val decoderSpec = ModelRegistry.getSpec("${modelName}_decoder")
                val tokensSpec = ModelRegistry.getSpec("whisper_tokens")

                if (!modelManager.isModelReady(encoderSpec) || !modelManager.isModelReady(decoderSpec) || !modelManager.isModelReady(tokensSpec)) {
                    Log.e(TAG, "Essential ASR files missing.")
                    return emptyList()
                }

                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = modelManager.getModelPath(encoderSpec),
                    decoder = modelManager.getModelPath(decoderSpec),
                    language = language,
                    task = "transcribe"
                )
                val modelConfig = OfflineModelConfig(
                    whisper = whisperConfig,
                    tokens = modelManager.getModelPath(tokensSpec),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
                offlineRecognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig, decodingMethod = "greedy_search"))
                currentAsrLanguage = language
                currentAsrModel = modelName
            } catch (e: Exception) {
                Log.e(TAG, "ASR Init failed", e)
                return emptyList()
            }
        }

        val recognizer = offlineRecognizer ?: return emptyList()
        val audioSamples = AudioDecoder.decodeToPcm(filePath, SAMPLE_RATE)

        if (audioSamples == null || audioSamples.isEmpty()) {
            Log.e(TAG, "Failed to decode audio file or file is empty.")
            return emptyList()
        }
        val finalSegments = mutableListOf<FinalTranscriptSegment>()
        val currentDiarizer = speakerDiarization

        if (currentDiarizer != null) {
            try {
                val segments = currentDiarizer.process(samples = audioSamples)
                val total = segments.size.toFloat()
                if (total == 0f) {
                    val text = decodeSegment(recognizer, audioSamples)
                    if(text.isNotBlank()) finalSegments.add(FinalTranscriptSegment(0, 0f, audioSamples.size/SAMPLE_RATE.toFloat(), text))
                } else {
                    segments.forEachIndexed { i, seg ->
                        val start = (seg.start * SAMPLE_RATE).toInt()
                        val end = (seg.end * SAMPLE_RATE).toInt().coerceAtMost(audioSamples.size)
                        if (end > start) {
                            val text = decodeSegment(recognizer, audioSamples.sliceArray(start until end))
                            if (text.isNotBlank()) finalSegments.add(FinalTranscriptSegment(seg.speaker, seg.start, seg.end, text))
                        }
                        onProgress((i + 1) / total)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Diarization crashed. Falling back to simple transcription.", e)
                val text = decodeSegment(recognizer, audioSamples)
                finalSegments.add(FinalTranscriptSegment(0, 0f, 0f, text))
            }
        } else {
            Log.i(TAG, "Running Minimum Resource Mode (ASR Only)")
            onProgress(0.5f)
            try {
                val text = decodeSegment(recognizer, audioSamples)
                if (text.isNotBlank()) {
                    val duration = audioSamples.size / SAMPLE_RATE.toFloat()
                    finalSegments.add(FinalTranscriptSegment(0, 0f, duration, text))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Simple transcription failed", e)
            }
            onProgress(1.0f)
        }

        return finalSegments
    }

    private fun decodeSegment(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples = samples, sampleRate = SAMPLE_RATE)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        stream.release()
        return result.text.trim()
    }

    private fun readWavFile(filePath: String): FloatArray? {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "WAV file does not exist: $filePath")
            return null
        }

        return try {
            FileInputStream(file).use { fileStream ->
                val headerBuffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                var bytesRead = fileStream.read(headerBuffer.array(), 0, 12)
                if (bytesRead < 12) return null
                val riffId = headerBuffer.getInt(0)
                val waveId = headerBuffer.getInt(8)
                if (riffId != 0x46464952 || waveId != 0x45564157) return null

                val chunkHeaderBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                var dataChunkSize: Int

                while (true) {
                    chunkHeaderBuffer.clear()
                    bytesRead = fileStream.read(chunkHeaderBuffer.array())
                    if (bytesRead < 8) return null

                    val chunkId = chunkHeaderBuffer.getInt(0)
                    val chunkSize = chunkHeaderBuffer.getInt(4)

                    if (chunkId == 0x61746164) {
                        dataChunkSize = chunkSize
                        break
                    }
                    val skipped = fileStream.skip(chunkSize.toLong())
                    if (skipped != chunkSize.toLong()) Log.w(TAG, "Could not skip entire chunk")
                }

                if (dataChunkSize <= 0) return null
                val dataBytes = ByteArray(dataChunkSize)
                bytesRead = fileStream.read(dataBytes)

                val buffer = ByteBuffer.wrap(dataBytes, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                val numSamples = bytesRead / 2
                val floatArray = FloatArray(numSamples)
                for (i in 0 until numSamples) {
                    floatArray[i] = buffer.short.toFloat() / 32768.0f
                }
                floatArray
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read WAV file", e)
            null
        }
    }

    fun close() {
        offlineRecognizer?.release()
        speakerDiarization?.release()
        speechEnhancer?.release()
    }
}