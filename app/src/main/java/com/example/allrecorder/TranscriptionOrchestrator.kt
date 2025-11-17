package com.example.allrecorder

import android.app.Application
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// This is the data class your ViewModels expect.
data class FinalTranscriptSegment(
    val speakerId: Int,
    val start: Float,
    val end: Float,
    val text: String
)

class TranscriptionOrchestrator(private val application: Application) {

    private var offlineRecognizer: OfflineRecognizer? = null
    private var speakerDiarization: OfflineSpeakerDiarization? = null
    private var currentAsrLanguage: String = ""
    private var currentAsrModel: String = ""
    private val asrModelPaths = mutableMapOf<String, AsrModelPaths>()
    private var whisperTokensPath: String = ""
    private var speechEnhancer: OfflineSpeechDenoiser? = null
    private var speechEnhancerPath: String = ""
    private data class AsrModelPaths(val encoder: String, val decoder: String)

    companion object {
        private const val TAG = "TranscriptionOrch"
        private const val MODEL_DIR = "sherpa_models"
        private const val SAMPLE_RATE = 16000
    }

    init {
        Log.i(TAG, "TranscriptionOrchestrator init block STARTED.")
        try {
            // --- First checkpoint ---
            Log.i(TAG, "init: Calling copyAssetsToCache()...")
            copyAssetsToCache()
            Log.i(TAG, "init: copyAssetsToCache() FINISHED.")

            // --- Second checkpoint ---
            Log.i(TAG, "init: Calling loadModels()...")
            loadModels()
            Log.i(TAG, "init: loadModels() FINISHED.")

        } catch (t: Throwable) {
            // This will catch *everything*, including Errors, not just Exceptions
            Log.e(TAG, "CRITICAL_INIT_FAILURE: A fatal error occurred during init.", t)
        }

        // --- Final checkpoint ---
        if (offlineRecognizer == null) {
            Log.e(TAG, "init: COMPLETED, but offlineRecognizer is STILL NULL.")
        }
        if (speakerDiarization == null) {
            Log.e(TAG, "init: COMPLETED, but speakerDiarization is STILL NULL.")
        }
        Log.i(TAG, "TranscriptionOrchestrator init block COMPLETED.")
    }

    private fun loadModels() {
        Log.i(TAG, "Loading Sherpa-Onnx models...")

        // --- 1. Configure and load Speaker Diarization (Unchanged) ---
        try {
            Log.i(TAG, "Attempting to load Speaker Diarization models...")
            val pyannoteConfig = OfflineSpeakerSegmentationPyannoteModelConfig(
                model = getAssetPath("segmentation.onnx")
            )

            val segmentationConfig = OfflineSpeakerSegmentationModelConfig(
                pyannote = pyannoteConfig,
                numThreads = 1
            )

            val embeddingConfig = SpeakerEmbeddingExtractorConfig(
                model = getAssetPath("wespeaker_en_voxceleb_resnet34_LM.onnx"),
                numThreads = 1
            )

            val clusteringConfig = FastClusteringConfig(
                numClusters = -1,
                threshold = 0.5f
            )

            val diarizationConfig = OfflineSpeakerDiarizationConfig(
                segmentation = segmentationConfig,
                embedding = embeddingConfig,
                clustering = clusteringConfig,
                minDurationOn = 0.3f,
                minDurationOff = 0.5f
            )

            speakerDiarization = OfflineSpeakerDiarization(config = diarizationConfig)
            Log.i(TAG, "SUCCESS: Speaker Diarization models loaded.")

        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL_ERROR: Failed to load Speaker Diarization models.", e)
        }

        // --- 2. Verify ALL ASR (Whisper) Model Paths ---
        try {
            Log.i(TAG, "Verifying ASR (Whisper) model paths...")

            // Get the shared tokens file
            whisperTokensPath = getAssetPath("whisper-tokens")
            speechEnhancerPath = getAssetPath("gtcrn_simple.onnx")
            Log.i(TAG, "Speech enhancer path verified.")

            // --- SYNTAX CHANGED HERE ---
            // Use .put() to add items to the map
            asrModelPaths.put("tiny", AsrModelPaths(
                encoder = getAssetPath("tiny-encoder.int8.onnx"),
                decoder = getAssetPath("tiny-decoder.int8.onnx")
            ))

            asrModelPaths.put("base", AsrModelPaths(
                encoder = getAssetPath("base-encoder.int8.onnx"),
                decoder = getAssetPath("base-decoder.int8.onnx")
            ))

            asrModelPaths.put("small", AsrModelPaths(
                encoder = getAssetPath("small-encoder.int8.onnx"),
                decoder = getAssetPath("small-decoder.int8.onnx")
            ))

            Log.i(TAG, "SUCCESS: All ASR (Whisper) model paths verified.")
        } catch (e: Exception) {
            // If any file is missing, this will log it.
            Log.e(TAG, "CRITICAL_ERROR: Failed to find ASR (Whisper) model files.", e)
        }
    }
    // --- UPDATED FUNCTION ---
    /**
     * Runs noise reduction on a WAV file and saves the result to a new temp file.
     * @return The path to the new, cleaned-up WAV file, or null on failure.
     */
    // --- UPDATED FUNCTION (v3) ---
    /**
     * Runs noise reduction on a WAV file and saves the result to a new temp file.
     * @return The path to the new, cleaned-up WAV file, or null on failure.
     */
    fun enhanceAudio(filePath: String): String? {
        Log.i(TAG, "Starting audio enhancement for $filePath")

        // 1. Lazily initialize the enhancer
        if (speechEnhancer == null) {
            try {
                Log.i(TAG, "Initializing SpeechEnhancer...")

                // --- START FIX ---
                // 1. Create the Gtcrn-specific config with the model path
                val gtcrnConfig = OfflineSpeechDenoiserGtcrnModelConfig(
                    model = speechEnhancerPath
                )

                // 2. Create the inner model config
                val modelConfig = OfflineSpeechDenoiserModelConfig(
                    gtcrn = gtcrnConfig,
                    numThreads = 1,
                    debug = false,
                    provider = "cpu"
                )

                // 3. Create the main denoiser config
                val config = OfflineSpeechDenoiserConfig(
                    model = modelConfig
                )
                // --- END FIX ---

                speechEnhancer = OfflineSpeechDenoiser(config = config)
                Log.i(TAG, "SpeechEnhancer initialized.")
            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL_ERROR: Failed to initialize SpeechEnhancer.", e)
                return null
            }
        }

        // 2. Read the audio
        val audioSamples = readWavFile(filePath)
        if (audioSamples == null) {
            Log.e(TAG, "Enhancement failed: Could not read audio file.")
            return null
        }

        // 3. Run enhancement
        try {
            val denoiser = speechEnhancer ?: return null
            Log.d(TAG, "Running denoiser...")

            // This is the core enhancement call
            val denoisedAudio = denoiser.run(samples = audioSamples, sampleRate = SAMPLE_RATE)
            Log.i(TAG, "Enhancement complete.")

            // 4. Save to a new temp file
            val tempFile = File.createTempFile("enhanced_", ".wav", application.cacheDir)

            // DenoisedAudio has its own save method
            denoisedAudio.save(filename = tempFile.absolutePath)

            Log.i(TAG, "Enhanced audio saved to: ${tempFile.absolutePath}")
            return tempFile.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL_ERROR: Speech enhancement processing failed.", e)
            return null
        }
    }


    // In TranscriptionOrchestrator.kt

    fun transcribe(
        filePath: String,
        language: String,
        modelName: String,
        // --- 1. ADD onProgress CALLBACK PARAMETER ---
        onProgress: (Float) -> Unit
    ): List<FinalTranscriptSegment> {

        // --- (ASR LOADER BLOCK - NO CHANGES) ---
        if (modelName != currentAsrModel || language != currentAsrLanguage || offlineRecognizer == null) {
            Log.i(TAG, "Config change detected: Model '$currentAsrModel' -> '$modelName', Lang '$currentAsrLanguage' -> '$language'.")
            Log.i(TAG, "Re-initializing ASR recognizer...")

            offlineRecognizer?.release()
            val modelPaths = asrModelPaths[modelName] ?: run {
                Log.e(TAG, "CRITICAL_ERROR: No paths found for model '$modelName'. Aborting.")
                return emptyList()
            }
            try {
                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = modelPaths.encoder,
                    decoder = modelPaths.decoder,
                    language = language,
                    task = "transcribe"
                )
                val modelConfig = OfflineModelConfig(
                    whisper = whisperConfig,
                    tokens = whisperTokensPath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
                val offlineConfig = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search"
                )
                offlineRecognizer = OfflineRecognizer(config = offlineConfig)
                currentAsrLanguage = language
                currentAsrModel = modelName
                Log.i(TAG, "SUCCESS: New ASR recognizer created for model '$modelName' / lang '$language'.")
            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL_ERROR: Failed to create ASR recognizer for '$modelName'/'$language'.", e)
                offlineRecognizer = null
                currentAsrLanguage = ""
                currentAsrModel = ""
                return emptyList()
            }
        }
        // --- (END OF ASR LOADER BLOCK) ---

        val recognizer = offlineRecognizer ?: run {
            Log.e(TAG, "Offline recognizer not initialized!")
            return emptyList()
        }
        val currentDiarizer = speakerDiarization ?: run {
            Log.e(TAG, "Speaker Diarizer not initialized!")
            return emptyList()
        }

        Log.i(TAG, "Starting transcription for $filePath with language '$language'")
        val finalSegments = mutableListOf<FinalTranscriptSegment>()

        val audioSamples = readWavFile(filePath)
        if (audioSamples == null) {
            Log.e(TAG, "Failed to read audio file.")
            return emptyList()
        }

        try {
            // --- 1. Perform Speaker Diarization ---
            Log.d(TAG, "Running diarization... Audio samples: ${audioSamples.size}")
            val segments = currentDiarizer.process(samples = audioSamples)
            Log.i(TAG, "Diarization complete. Found ${segments.size} segments.")

            val totalSegments = segments.size.toFloat()
            if (totalSegments == 0f) {
                onProgress(1.0f) // No segments, just report 100% done
                return emptyList()
            }

            // --- 2. Perform ASR on each segment ---
            segments.forEachIndexed { index, segment ->
                val startSample = (segment.start * SAMPLE_RATE).toInt()
                val endSample = (segment.end * SAMPLE_RATE).toInt().coerceAtMost(audioSamples.size)

                if (endSample > startSample) {
                    val segmentSamples = audioSamples.sliceArray(startSample until endSample)

                    val stream = recognizer.createStream()
                    stream.acceptWaveform(samples = segmentSamples, sampleRate = SAMPLE_RATE)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    val text = result.text.trim()
                    stream.release()

                    if (text.isNotBlank()) {
                        val finalSegment = FinalTranscriptSegment(
                            speakerId = segment.speaker,
                            start = segment.start,
                            end = segment.end,
                            text = text
                        )
                        finalSegments.add(finalSegment)
                        Log.d(
                            TAG,
                            "Segment: Speaker ${finalSegment.speakerId}, ${finalSegment.text}"
                        )
                    }
                }

                // --- 2. REPORT PROGRESS ---
                // Calculate progress (0.0 to 1.0)
                val progress = (index + 1).toFloat() / totalSegments
                onProgress(progress)

            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription/Diarization failed", e)
            onProgress(1.0f) // Report complete even on failure to clear the bar
        }

        Log.i(TAG, "Transcription finished. Generated ${finalSegments.size} segments.")
        return finalSegments
    }
    /**
     * Reads a 16kHz, 16-bit PCM mono WAV file and returns it as a FloatArray.
     * This version is more robust and parses the header to find the 'data' chunk.
     */
    private fun readWavFile(filePath: String): FloatArray? {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "WAV file does not exist: $filePath")
            return null
        }

        return try {
            FileInputStream(file).use { fileStream ->
                val headerBuffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)

                // 1. Read "RIFF", file size, "WAVE"
                var bytesRead = fileStream.read(headerBuffer.array(), 0, 12)
                if (bytesRead < 12) {
                    Log.e(TAG, "File is too small to be a WAV file")
                    return null
                }

                // Check for "RIFF" and "WAVE"
                val riffId = headerBuffer.getInt(0)
                val waveId = headerBuffer.getInt(8)

                if (riffId != 0x46464952 || waveId != 0x45564157) {
                    Log.e(
                        TAG,
                        "Not a valid RIFF/WAVE file. RIFF: ${riffId.toString(16)}, WAVE: ${
                            waveId.toString(16)
                        }"
                    )
                    return null
                }

                // 2. Find the 'data' chunk
                val chunkHeaderBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                var dataChunkSize: Int

                while (true) {
                    chunkHeaderBuffer.clear()
                    bytesRead = fileStream.read(chunkHeaderBuffer.array())
                    if (bytesRead < 8) {
                        Log.e(TAG, "Reached end of file without finding 'data' chunk")
                        return null
                    }

                    val chunkId = chunkHeaderBuffer.getInt(0)
                    val chunkSize = chunkHeaderBuffer.getInt(4)

                    if (chunkId == 0x61746164) { // "data" chunk
                        dataChunkSize = chunkSize
                        Log.i(TAG, "'data' chunk found. Size: $dataChunkSize bytes")
                        break
                    }

                    // Not 'data', skip this chunk's content
                    val skipped = fileStream.skip(chunkSize.toLong())
                    if (skipped != chunkSize.toLong()) {
                        Log.w(TAG, "Could not skip entire chunk")
                    }
                }

                // 3. Read the audio data
                if (dataChunkSize <= 0) {
                    Log.e(TAG, "Data chunk has no size")
                    return null
                }

                val dataBytes = ByteArray(dataChunkSize)
                bytesRead = fileStream.read(dataBytes)

                if (bytesRead < dataChunkSize) {
                    Log.w(
                        TAG,
                        "Warning: read $bytesRead bytes, but data chunk size was $dataChunkSize"
                    )
                }

                // 4. Convert 16-bit PCM bytes to FloatArray
                val buffer = ByteBuffer.wrap(dataBytes, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                val numSamples = bytesRead / 2 // 2 bytes per 16-bit sample
                val floatArray = FloatArray(numSamples)

                for (i in 0 until numSamples) {
                    // Normalize to [-1.0, 1.0]
                    floatArray[i] = buffer.short.toFloat() / 32768.0f
                }

                Log.i(TAG, "Successfully read WAV file: $numSamples samples")
                floatArray
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read WAV file: $filePath", e)
            null
        }
    }

    private fun getAssetPath(assetName: String): String {
        val file = File(application.cacheDir, assetName)
        if (!file.exists()) {
            // This new log is crucial
            Log.e(TAG, "Asset file not found in cache: ${file.absolutePath}")
            throw RuntimeException("Asset not found in cache: $assetName. Did copyAssetsToCache() run?")
        }
        Log.d(TAG, "Asset path verified for: $assetName")
        return file.absolutePath
    }

    private fun copyAssetsToCache() {
        val assetManager = application.assets
        val modelFiles = listOf(
            // --- Diarization Models ---
            "segmentation.onnx",
            "wespeaker_en_voxceleb_resnet34_LM.onnx",

            // --- Whisper Models ---
            "whisper-tokens", // Renamed from tiny-tokens

            "tiny-encoder.int8.onnx",
            "tiny-decoder.int8.onnx",

            "base-encoder.int8.onnx",
            "base-decoder.int8.onnx",

            "small-encoder.int8.onnx",
            "small-decoder.int8.onnx",
            "gtcrn_simple.onnx"
        )

        Log.i(TAG, "Checking cache for $MODEL_DIR models...")
        modelFiles.forEach { filename ->
            val file = File(application.cacheDir, filename)
            if (file.exists()) {
                Log.d(TAG, "$filename already in cache.")
                return@forEach // 'continue' for a forEach loop
            }

            try {
                Log.i(TAG, "Copying $filename from assets to cache...")
                assetManager.open("$MODEL_DIR/$filename").use { inStream ->
                    FileOutputStream(file).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                Log.i(TAG, "SUCCESS: Copied $filename to cache.")
            } catch (e: Exception) {
                // This is the new, specific error log
                Log.e(TAG, "CRITICAL_ERROR: Failed to copy $filename from assets/$MODEL_DIR", e)
                // This is often a FileNotFoundException if the path or filename is wrong
            }
        }
    }

    // Call this from your ViewModel's onCleared() to free resources
    // Call this from your ViewModel's onCleared() to free resources
    fun close() {
        offlineRecognizer?.release() // This is the important line
        offlineRecognizer = null

        speakerDiarization?.release()
        speakerDiarization = null

        speechEnhancer?.release()
        speechEnhancer = null

        Log.i(TAG, "Sherpa-Onnx models released.")
    }
}