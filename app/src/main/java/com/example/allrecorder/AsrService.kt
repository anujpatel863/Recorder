package com.example.allrecorder

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Collections
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.exp
import kotlin.math.max // Needed for log_softmax stability

class AsrService(private val context: Context) {

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    val preprocessor: AudioPreprocessor // Ensure this uses utterance normalization now
    val vocab: IndicVocab // Ensure this has corrected blank/sos IDs

    // --- Model Sessions ---
    private var encoderSession: OrtSession? = null
    private var rnntDecoderSession: OrtSession? = null
    private var jointEncSession: OrtSession? = null
    private var jointPredSession: OrtSession? = null
    private var jointPreNetSession: OrtSession? = null
    private val postNetSessions = mutableMapOf<String, OrtSession?>()
    private var ctcDecoderSession: OrtSession? = null // Keep CTC as well

    // --- State ---
    private var areRnntModelsLoaded = false
    private var isCtcDecoderLoaded = false // Keep CTC state
    private val loadingLock = Any()

    // --- Constants (Match Python Script) ---
    private val rnntMaxSymbolsPerStep = 10
    private val predRnnLayers = 2
    private val predRnnHiddenDim = 640 // Example: Joint/Decoder hidden dimension
    private val TARGET_SAMPLE_RATE = 16000

    companion object {
        private const val TAG = "AsrService"
    }

    init {
        preprocessor = AudioPreprocessor(context) // Assumes correct implementation
        vocab = IndicVocab(context) // Assumes corrected IDs
        Log.d(TAG, "AsrService initialized.")
        // Optionally pre-load models here if desired
        // ensureRnntModelsLoaded()
        // ensureCtcModelsLoaded()
    }

    // --- Lazy Loading Functions (Keep existing logic, ensure filenames match) ---
    // --- Make sure these load the *quantized* encoder if that's what python uses ---
    @Synchronized
    private fun ensureRnntModelsLoaded(): Boolean {
        synchronized(loadingLock) {
            if (areRnntModelsLoaded && !areAnyRnntSessionsClosed()) return true
            Log.i(TAG, "Starting lazy loading of RNN-T ONNX models...")
            try {
                // *** IMPORTANT: Match USE_QUANTIZED_ENCODER = True from Python ***
                val encoderFileName = "indic_model/encoder.quant.int8.onnx"
                Log.d(TAG, "Attempting to load encoder: $encoderFileName")
                if (encoderSession.isClosedOrNull()) {
                    encoderSession = createSessionInternal(encoderFileName)
                }

                if (rnntDecoderSession.isClosedOrNull()) {
                    rnntDecoderSession = createSessionInternal("indic_model/rnnt_decoder.onnx")
                }
                if (jointEncSession.isClosedOrNull()) {
                    jointEncSession = createSessionInternal("indic_model/joint_enc.onnx")
                }
                if (jointPredSession.isClosedOrNull()) {
                    jointPredSession = createSessionInternal("indic_model/joint_pred.onnx")
                }
                if (jointPreNetSession.isClosedOrNull()) {
                    jointPreNetSession = createSessionInternal("indic_model/joint_pre_net.onnx")
                }
                // Pre-load a default post-net if desired, or load on demand
                // getPostNetSessionInternal("gu")

                areRnntModelsLoaded = !areAnyRnntSessionsClosed()
                if (areRnntModelsLoaded) {
                    Log.i(TAG, "RNN-T models loaded successfully.")
                } else {
                    Log.e(TAG, "One or more RNN-T models failed to load.")
                    closeRnntSessions() // Clean up partially loaded models
                }
                return areRnntModelsLoaded
            } catch (e: Exception) {
                Log.e(TAG, "Exception during RNN-T model loading", e)
                closeRnntSessions()
                areRnntModelsLoaded = false
                return false
            }
        }
    }

    @Synchronized
    private fun ensureCtcModelsLoaded(): Boolean {
        synchronized(loadingLock) {
            if (isCtcDecoderLoaded && !encoderSession.isClosedOrNull() && !ctcDecoderSession.isClosedOrNull()) { return true }
            Log.i(TAG, "Starting lazy loading of CTC ONNX models...")
            try {
                // Reuse encoder if loaded by RNNT, otherwise load it
                val encoderFileName = "indic_model/encoder.quant.int8.onnx" // Assume quantized
                if (encoderSession.isClosedOrNull()) {
                    encoderSession = createSessionInternal(encoderFileName)
                }
                if (ctcDecoderSession.isClosedOrNull()) {
                    ctcDecoderSession = createSessionInternal("indic_model/ctc_decoder.onnx")
                }

                isCtcDecoderLoaded = !encoderSession.isClosedOrNull() && !ctcDecoderSession.isClosedOrNull()
                if (isCtcDecoderLoaded) {
                    Log.i(TAG, "CTC models loaded successfully.")
                } else {
                    Log.e(TAG, "Encoder or CTC Decoder failed to load.")
                    closeCtcSessions()
                }
                return isCtcDecoderLoaded
            } catch (e: Exception) {
                Log.e(TAG, "Exception during CTC model loading", e)
                closeCtcSessions()
                isCtcDecoderLoaded = false
                return false
            }
        }
    }


    // Helper to check if any core RNNT session is closed
    private fun areAnyRnntSessionsClosed(): Boolean {
        return encoderSession.isClosedOrNull() ||
                rnntDecoderSession.isClosedOrNull() ||
                jointEncSession.isClosedOrNull() ||
                jointPredSession.isClosedOrNull() ||
                jointPreNetSession.isClosedOrNull()
    }
    // Helper function to close only RNNT related sessions
    private fun closeRnntSessions() {
        rnntDecoderSession?.close(); rnntDecoderSession = null
        jointEncSession?.close(); jointEncSession = null
        jointPredSession?.close(); jointPredSession = null
        jointPreNetSession?.close(); jointPreNetSession = null
        postNetSessions.values.forEach { it?.close() }
        postNetSessions.clear()
        // Don't close encoderSession here if CTC might use it
    }
    // Helper function to close only CTC related sessions
    private fun closeCtcSessions() {
        ctcDecoderSession?.close(); ctcDecoderSession = null
        // Don't close encoderSession here if RNNT might use it
    }

    // --- (createSessionInternal, copyAssetToFile, getPostNetSessionInternal, isClosedOrNull - Keep existing implementations) ---
    private fun createSessionInternal(assetPath: String): OrtSession? {
        Log.d(TAG, "Attempting to load model by copying from asset path: $assetPath")
        val modelFile: File? = try { copyAssetToFile(assetPath) } catch (e: Exception) { Log.e(TAG, "Failed to copy asset $assetPath to cache", e); null }
        if (modelFile == null || !modelFile.exists()) { Log.e(TAG, "Model file is null or does not exist after copying."); return null }

        // --- Crucial Fix for Quantized Models: Copy .data file if it exists ---
        val dataAssetPath = "$assetPath.data"
        try {
            val parentDir = File(assetPath).parent ?: ""
            val assetFileName = File(assetPath).name + ".data"
            val assetList = context.assets.list(parentDir)

            if (assetList != null && assetList.contains(assetFileName)) {
                copyAssetToFile(dataAssetPath) // Copy the .data file to cache
                Log.d(TAG, "Copied associated .data file: $dataAssetPath")
            } else {
                Log.d(TAG, "No associated .data file found for $assetPath in assets/$parentDir (this is okay if model isn't quantized)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check for or copy .data file $dataAssetPath", e)
        }
        // --- End Fix ---

        Log.d(TAG, "Loading model from copied file path: ${modelFile.absolutePath}")
        return try { ortEnvironment.createSession(modelFile.absolutePath, SessionOptions()) }
        catch (e: Exception) { Log.e(TAG, "Failed to load model from file ${modelFile.absolutePath}", e); null }
    }

    @Throws(Exception::class)
    private fun copyAssetToFile(assetPath: String): File {
        val assetFileName = File(assetPath).name
        val outFile = File(context.cacheDir, assetFileName)
        // Overwrite if exists to ensure latest version
        if (outFile.exists()) {
            outFile.delete()
        }
        context.assets.open(assetPath).use { iStream ->
            FileOutputStream(outFile).use { oStream ->
                iStream.copyTo(oStream)
            }
        }
        Log.d(TAG, "Copied asset '$assetPath' to '${outFile.absolutePath}'")
        return outFile
    }
    private fun getPostNetSessionInternal(language: String): OrtSession? {
        // Check if already loaded and not closed
        if (postNetSessions.containsKey(language) && !postNetSessions[language].isClosedOrNull()) {
            return postNetSessions[language]
        }
        // Attempt to load
        val assetPath = "indic_model/joint_post_net_$language.onnx"
        Log.d(TAG, "Loading post-net for language '$language' from $assetPath")
        val session = createSessionInternal(assetPath)
        postNetSessions[language] = session // Store session (even if null)
        if (session == null) {
            Log.e(TAG, "Failed to load post-net for language: $language")
        } else {
            Log.d(TAG,"Successfully loaded post-net for language: $language")
        }
        return session
    }
    private fun OrtSession?.isClosedOrNull(): Boolean {
        // An OrtSession is never truly "closed" in the API, it's just released.
        // Checking for null is the effective way to see if it needs reloading.
        return this == null
    }

    // --- (Audio reading/resampling - Keep existing implementations) ---
    private fun readAudioFile(filePath: String): FloatArray { /* ... Keep implementation ... */
        val extractor = MediaExtractor()
        val audioSamples = mutableListOf<Float>()
        var originalSampleRate = -1

        try {
            extractor.setDataSource(filePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: -1

            if (trackIndex == -1) {
                Log.e(TAG, "No audio track found in $filePath")
                return FloatArray(0)
            }
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            originalSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.d(TAG, "Reading audio: $filePath, Rate: $originalSampleRate, Channels: $channelCount")


            val buffer = ByteBuffer.allocate(1024 * 16).order(ByteOrder.nativeOrder()) // Larger buffer
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize <= 0) break
                buffer.rewind()
                // Handle stereo: average channels to mono
                if (channelCount == 2) {
                    while (buffer.remaining() >= 4) { // 2 channels * 2 bytes/short
                        val left = buffer.short.toFloat() / 32768.0f
                        val right = buffer.short.toFloat() / 32768.0f
                        audioSamples.add((left + right) / 2.0f) // Average channels
                    }
                } else { // Mono or other (treat as mono)
                    while (buffer.remaining() >= 2) {
                        audioSamples.add(buffer.short.toFloat() / 32768.0f) // Normalize
                    }
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

        val readSamples = audioSamples.toFloatArray()
        Log.d(TAG,"Read ${readSamples.size} samples.")

        // Resample if necessary
        if (originalSampleRate != TARGET_SAMPLE_RATE && originalSampleRate > 0) {
            Log.d(TAG, "Resampling audio from $originalSampleRate Hz to $TARGET_SAMPLE_RATE Hz")
            return resampleLinear(readSamples, originalSampleRate, TARGET_SAMPLE_RATE)
        } else if (originalSampleRate <= 0) {
            Log.e(TAG, "Could not determine original sample rate.")
            return FloatArray(0) // Return empty on error
        } else {
            Log.d(TAG, "Audio already at target sample rate ($TARGET_SAMPLE_RATE Hz).")
            return readSamples // Already at target rate
        }
    }
    private fun resampleLinear(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray { /* ... Keep implementation ... */
        val inputLength = input.size
        if (inputLength == 0 || inputRate <= 0 || outputRate <= 0) return FloatArray(0)

        val outputLength = (inputLength.toLong() * outputRate / inputRate).toInt()
        if (outputLength <= 0) return FloatArray(0)

        val output = FloatArray(outputLength)
        val step = inputRate.toDouble() / outputRate.toDouble() // Ratio of input samples per output sample
        var currentInputIndex = 0.0 // Position in the input array (can be fractional)

        for (i in 0 until outputLength) {
            val indexFloor = floor(currentInputIndex).toInt() // Index of the sample before the current position
            val indexCeil = indexFloor + 1                   // Index of the sample after the current position
            val fraction = currentInputIndex - indexFloor    // How far between indexFloor and indexCeil we are

            // Get the values at floor and ceil, handling boundary conditions
            val valueFloor = input.getOrElse(indexFloor) { 0f } // Use 0f if index is out of bounds
            val valueCeil = input.getOrElse(indexCeil) { 0f }   // Use 0f if index is out of bounds

            // Linear interpolation
            output[i] = (valueFloor * (1.0 - fraction) + valueCeil * fraction).toFloat()

            currentInputIndex += step // Move to the next position in the input array
        }
        Log.d(TAG,"Resampling done. Output size: ${output.size}")
        return output
    }
    // Keep transposeFloatArray, needed for RNNT
    private fun transposeFloatArray(data: FloatArray, shape: LongArray, permutation: IntArray): Pair<FloatArray, LongArray> { /* ... Keep implementation ... */
        if (shape.size != 3 || permutation.size != 3) {
            throw IllegalArgumentException("Only 3D transpose is supported for this helper.")
        }
        val (d1, d2, d3) = shape.map { it.toInt() }
        val (p1, p2, p3) = permutation // e.g., (0, 2, 1)

        // Calculate new shape based on permutation
        val newShape = longArrayOf(shape[p1], shape[p2], shape[p3])
        val (nd1, nd2, nd3) = newShape.map { it.toInt() }

        if (d1 * d2 * d3 != data.size) {
            throw IllegalArgumentException("Data size does not match shape: ${data.size} vs ${shape.contentToString()}")
        }
        if (nd1 * nd2 * nd3 != data.size) {
            // This should mathematically not happen if permutation is valid
            throw IllegalArgumentException("New shape calculation error.")
        }


        val newData = FloatArray(data.size)
        var newIndex = 0

        // Iterate through the *new* dimensions
        for (i in 0 until nd1) {
            for (j in 0 until nd2) {
                for (k in 0 until nd3) {
                    // Map back to original indices
                    val originalIndices = IntArray(3)
                    originalIndices[p1] = i // Index i in the new dim p1 corresponds to original dim p1
                    originalIndices[p2] = j // Index j in the new dim p2 corresponds to original dim p2
                    originalIndices[p3] = k // Index k in the new dim p3 corresponds to original dim p3
                    val (o1, o2, o3) = originalIndices // Original indices

                    // Calculate the flat index in the original data array
                    val originalIndex = o1 * (d2 * d3) + o2 * d3 + o3

                    if (originalIndex >= 0 && originalIndex < data.size) {
                        newData[newIndex++] = data[originalIndex]
                    } else {
                        // This should ideally not happen with correct logic, but log if it does
                        Log.e(TAG, "Transpose index calculation error: $originalIndex out of bounds (size ${data.size}) for new index ($i, $j, $k)")
                        newData[newIndex++] = 0f // Or throw exception
                    }
                }
            }
        }

        if (newIndex != data.size) {
            // This indicates a potential logic error in the loops
            Log.w(TAG, "Transpose output size mismatch: expected ${data.size}, got $newIndex. Shape: ${shape.contentToString()}, NewShape: ${newShape.contentToString()}")
        }

        return Pair(newData, newShape)
    }


    // --- runEncoder (Keep existing implementation, ensure Int64 length tensor) ---
    private fun runEncoder(filePath: String): Triple<FloatArray, LongArray, LongArray?>? {
        Log.d(TAG, "Starting runEncoder for: $filePath")
        val audioFloats = readAudioFile(filePath)
        if (audioFloats.isEmpty()) {
            Log.e(TAG, "Audio read or resampling failed.")
            return null
        }

        Log.d(TAG, "Running AudioPreprocessor...")
        val melSpectrogramFlat = preprocessor.process(audioFloats)
        val numFrames = if (preprocessor.nMels > 0 && melSpectrogramFlat.isNotEmpty()) melSpectrogramFlat.size / preprocessor.nMels else 0
        Log.d(TAG,"Preprocessor output size: ${melSpectrogramFlat.size}, NumFrames: $numFrames")

        if (numFrames == 0) {
            Log.e(TAG, "Preprocessing error: 0 frames generated.")
            return null
        }

        // Input shape expected by model: [1, Mels, Frames]
        val shape = longArrayOf(1, preprocessor.nMels.toLong(), numFrames.toLong())
        var melTensor: OnnxTensor? = null
        var lengthTensor: OnnxTensor? = null
        var result: Triple<FloatArray, LongArray, LongArray?>? = null

        try {
            melTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(melSpectrogramFlat), shape)

            // --- FIX: Ensure length is Int64 (Long) as required by this specific model ---
            lengthTensor = OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(longArrayOf(numFrames.toLong())), longArrayOf(1))
            Log.d(TAG,"Created tensors - Mel shape: ${shape.contentToString()}, Length shape: [1]")
            // --- End Fix ---

            Log.d(TAG, "Running encoder model...")
            val encoderInputs = mapOf(
                "audio_signal" to melTensor, // Check ONNX model input names (e.g., using Netron)
                "length" to lengthTensor     // Check ONNX model input names
            )

            encoderSession!!.run(encoderInputs).use { results ->
                Log.d(TAG,"Encoder run successful. Processing results...")
                // Assuming encoder outputs 'outputs' and 'encoded_lengths'
                // Check actual output names using Netron or model inspection if needed
                val outputsTensor = results.get("outputs").get() as OnnxTensor
                val lengthsTensor = results.get("encoded_lengths").get() as OnnxTensor // Should be Long

                Log.d(TAG,"Encoder output shapes: Outputs=${outputsTensor.info.shape.contentToString()}, Lengths=${lengthsTensor.info.shape.contentToString()}")

                val outputsArray = outputsTensor.floatBuffer.array() // Get underlying array efficiently
                val outputShape = outputsTensor.info.shape

                // Ensure lengths are read as Long
                val lengthsBuffer = lengthsTensor.longBuffer
                val lengthsArray = LongArray(lengthsBuffer.remaining())
                lengthsBuffer.get(lengthsArray)


                result = Triple(outputsArray, outputShape, lengthsArray)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder run failed", e)
            result = null // Ensure result is null on error
        } finally {
            melTensor?.close() // Safely close tensors
            lengthTensor?.close()
        }

        if (result != null) {
            Log.d(TAG, "Encoder done. Output shape: ${result.second.contentToString()}, Reported output frames: ${result.third?.firstOrNull()}")
        }
        return result
    }


    /**
     * Replicates the RNNT inference logic from simple_inference.py
     */
    suspend fun transcribeRnnt(filePath: String, language: String): String {
        Log.i(TAG, "Starting RNNT transcription for '$language' on file: $filePath")
        if (!ensureRnntModelsLoaded()) {
            return "[Error: RNN-T Models could not be loaded or are invalid]"
        }
        val postNet = getPostNetSessionInternal(language)
        if (postNet == null) {
            return "[Error: Post-net model for '$language' not found or failed to load]"
        }

        // 1. Run Encoder (Handles preprocessing)
        val encoderResult = runEncoder(filePath)
        if (encoderResult == null) {
            return "[Encoder Failed]"
        }
        val (encoderOutputs, encoderShape, encodedLengthsLong) = encoderResult
        // encodedLengthsLong is now correctly LongArray?
        if (encodedLengthsLong == null || encodedLengthsLong.isEmpty()) {
            Log.e(TAG, "Encoder did not return valid output lengths.")
            return "[Encoder Lengths Error]"
        }
        val encodedLength = encodedLengthsLong[0].toInt() // Use the first (and only) length value
        Log.d(TAG, "Encoder output shape: ${encoderShape.contentToString()}, Effective length: $encodedLength")


        // --- Intermediate tensors, managed with try-with-resources (.use) ---
        var jointEncInputTensor: OnnxTensor? = null
        var fTensor: OnnxTensor? = null
        var targetTensor: OnnxTensor? = null
        var targetLengthTensor: OnnxTensor? = null
        var hTensor: OnnxTensor? = null
        var cTensor: OnnxTensor? = null
        var gPredInputTensor: OnnxTensor? = null
        var combinedTensor: OnnxTensor? = null
        var preNetInputTensor : OnnxTensor? = null
        var preNetOutputTensorForPostNet: OnnxTensor? = null


        try {
            // 2. Prepare for Joint Encoder
            Log.d(TAG, "Preparing for Joint Encoder...")
            // Transpose encoder output: [1, Features, Time] -> [1, Time, Features]
            val (encoderTransposedData, encoderTransposedShape) = transposeFloatArray(
                encoderOutputs, encoderShape, intArrayOf(0, 2, 1) // Permutation: Batch, Time, Features
            )
            Log.d(TAG, "Transposed encoder output shape: ${encoderTransposedShape.contentToString()}")

            jointEncInputTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(encoderTransposedData), encoderTransposedShape)

            // 3. Run Joint Encoder (joint_enc.onnx) -> Produces 'f_base'
            Log.d(TAG, "Running Joint Encoder...")
            val f_base: FloatArray // Shape [1, Time, joint_hidden_dim (e.g., 640)]
            jointEncSession!!.run(Collections.singletonMap("input", jointEncInputTensor)).use { results ->
                (results[0] as OnnxTensor).use { outputTensor ->
                    Log.d(TAG, "Joint Encoder output shape: ${outputTensor.info.shape.contentToString()}")
                    val buffer = outputTensor.floatBuffer
                    f_base = FloatArray(buffer.remaining())
                    buffer.get(f_base)
                }
            }
            jointEncInputTensor.close(); jointEncInputTensor = null // Close immediately after use

            val jointHiddenDim = if (encoderTransposedShape[1] > 0) f_base.size / encoderTransposedShape[1].toInt() else 0
            if(jointHiddenDim != predRnnHiddenDim) {
                Log.w(TAG,"Potential mismatch: Joint Encoder output dim ($jointHiddenDim) != PRED_RNN_HIDDEN_DIM ($predRnnHiddenDim)")
                // Adjust predRnnHiddenDim if necessary, though they should match
            }
            Log.d(TAG,"Joint Encoder output processed. Shape [1, ${encoderTransposedShape[1]}, $jointHiddenDim]")


            // 4. RNNT Decode Loop Initialization
            Log.d(TAG, "Initializing RNNT decode loop state...")
            val hypothesis = mutableListOf(vocab.sosId) // Start with SOS ID
            var hState = FloatArray(predRnnLayers * 1 * predRnnHiddenDim) { 0f } // Match Python init
            var cState = FloatArray(predRnnLayers * 1 * predRnnHiddenDim) { 0f } // Match Python init
            val stateShape = longArrayOf(predRnnLayers.toLong(), 1, predRnnHiddenDim.toLong()) // Shape for state tensors

            val fFrame = FloatArray(predRnnHiddenDim) // Buffer for current 'f' frame
            val actualFramesInFBase = if (predRnnHiddenDim > 0) f_base.size / predRnnHiddenDim else 0

            // Use the length reported by the encoder, capped by the actual f_base size
            val loopLimit = min(encodedLength, actualFramesInFBase)
            if (encodedLength > actualFramesInFBase) {
                Log.w(TAG, "Encoder reported length ($encodedLength) > actual frames in f_base ($actualFramesInFBase). Using $actualFramesInFBase.")
            } else if (encodedLength < actualFramesInFBase) {
                Log.w(TAG,"Encoder reported length ($encodedLength) < actual frames in f_base ($actualFramesInFBase). Using $encodedLength.")
            }

            Log.d(TAG, "Starting RNNT decode loop for $loopLimit frames...")

            // 5. Main Decoding Loop (Outer loop over time 't')
            for (t in 0 until loopLimit) {
                // Get the current frame 'f' from the joint encoder output
                try {
                    System.arraycopy(f_base, t * predRnnHiddenDim, fFrame, 0, predRnnHiddenDim)
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying f_base frame at t=$t", e)
                    return "[Error: Array copy failed in decode loop]"
                }
                // fTensor shape [1, 1, predRnnHiddenDim]
                fTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(fFrame), longArrayOf(1, 1, predRnnHiddenDim.toLong()))

                var symbolsAdded = 0
                var notBlank = true

                // Inner loop (while predicted token is not blank)
                while (notBlank && symbolsAdded < rnntMaxSymbolsPerStep) {
                    try {
                        // --- Prepare Inputs for RNNT Decoder ---
                        val lastTokenInt = hypothesis.last()
                        // targets: [1, 1], value = last token ID
                        targetTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(intArrayOf(lastTokenInt)), longArrayOf(1, 1))
                        // target_length: [1], value = 1
                        targetLengthTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1))
                        // states: [num_layers, 1, hidden_dim]
                        hTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(hState), stateShape)
                        cTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(cState), stateShape)

                        // Input names must match the rnnt_decoder.onnx model
                        // Use Netron to verify names like "states.1", "onnx::Slice_3"
                        val decoderInputs = mapOf(
                            "targets" to targetTensor,
                            "target_length" to targetLengthTensor,
                            "states.1" to hTensor, // Verify this name
                            "onnx::Slice_3" to cTensor // Verify this name
                        )

                        // --- Run RNNT Decoder ---
                        Log.v(TAG, "t=$t, symbol=$symbolsAdded: Running RNNT Decoder...")
                        val newH: FloatArray
                        val newC: FloatArray
                        val gOutputData: FloatArray // Shape [1, predRnnHiddenDim, 1] ? Check Netron/Python output
                        val gOutputShape: LongArray // Get shape from output tensor

                        rnntDecoderSession!!.run(decoderInputs).use { decoderResults ->
                            // Output names must match rnnt_decoder.onnx
                            // Verify indices/names: 0='outputs', 2='states', 3='162' ?
                            val gOutTensor = decoderResults.get("outputs").get() as OnnxTensor // Verify name
                            val hOutTensor = decoderResults.get("states").get() as OnnxTensor // Verify name
                            val cOutTensor = decoderResults.get("162").get() as OnnxTensor // Verify name

                            Log.v(TAG, "   Decoder shapes: g=${gOutTensor.info.shape.contentToString()}, h=${hOutTensor.info.shape.contentToString()}, c=${cOutTensor.info.shape.contentToString()}")

                            newH = hOutTensor.floatBuffer.array()
                            newC = cOutTensor.floatBuffer.array()
                            gOutputShape = gOutTensor.info.shape
                            val gBuffer = gOutTensor.floatBuffer
                            gOutputData = FloatArray(gBuffer.remaining())
                            gBuffer.get(gOutputData)
                        } // decoderResults closed

                        // --- Run Joint Prediction Network ---
                        // Transpose gOutput: Python uses (0, 2, 1) -> [1, 1, predRnnHiddenDim]
                        Log.v(TAG, "   Running Joint Prediction...")
                        val (gTransposedData, gTransposedShape) = transposeFloatArray(gOutputData, gOutputShape, intArrayOf(0, 2, 1))
                        gPredInputTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(gTransposedData), gTransposedShape)

                        val gFloats: FloatArray // Shape [1, 1, predRnnHiddenDim]
                        jointPredSession!!.run(Collections.singletonMap("input", gPredInputTensor)).use { gResults ->
                            (gResults[0] as OnnxTensor).use { gTemp ->
                                Log.v(TAG, "   Joint Pred output shape: ${gTemp.info.shape.contentToString()}")
                                val gBuffer = gTemp.floatBuffer
                                gFloats = FloatArray(gBuffer.remaining())
                                gBuffer.get(gFloats)
                            }
                        } // gResults closed
                        gPredInputTensor.close(); gPredInputTensor = null

                        // --- Combine f and g ---
                        if (fFrame.size != gFloats.size) {
                            Log.e(TAG,"Shape mismatch before combine: fFrame=${fFrame.size}, gFloats=${gFloats.size}")
                            return "[Error: F/G shape mismatch]"
                        }
                        val combinedData = FloatArray(predRnnHiddenDim) { i -> fFrame[i] + gFloats[i] }
                        // Shape [1, 1, predRnnHiddenDim]
                        combinedTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(combinedData), longArrayOf(1, 1, predRnnHiddenDim.toLong()))

                        // --- Run Joint Pre-Net ---
                        Log.v(TAG, "   Running Joint PreNet...")
                        val preNetOutData: FloatArray
                        val preNetOutShape: LongArray
                        jointPreNetSession!!.run(Collections.singletonMap("input", combinedTensor)).use { preNetResults ->
                            (preNetResults[0] as OnnxTensor).use { preNetOutTemp ->
                                preNetOutShape = preNetOutTemp.info.shape
                                Log.v(TAG,"   Joint PreNet output shape: ${preNetOutShape.contentToString()}")
                                val preNetBuffer = preNetOutTemp.floatBuffer
                                preNetOutData = FloatArray(preNetBuffer.remaining())
                                preNetBuffer.get(preNetOutData)
                            }
                        } // preNetResults closed
                        combinedTensor.close(); combinedTensor = null
                        preNetInputTensor = OnnxTensor.createTensor(ortEnvironment,FloatBuffer.wrap(preNetOutData),preNetOutShape)

                        // --- Run Joint Post-Net (Language Specific) ---
                        Log.v(TAG, "   Running Joint PostNet ($language)...")
                        val logitsData: FloatArray
                        val logitsShape: LongArray
                        preNetOutputTensorForPostNet = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(preNetOutData), preNetOutShape)
                        postNet.run(Collections.singletonMap("input", preNetOutputTensorForPostNet)).use { logitsResults ->
                            (logitsResults[0] as OnnxTensor).use { logitsTensor ->
                                logitsShape = logitsTensor.info.shape
                                Log.v(TAG,"   PostNet output shape (logits): ${logitsShape.contentToString()}")
                                val logitsBuffer = logitsTensor.floatBuffer
                                logitsData = FloatArray(logitsBuffer.remaining())
                                logitsBuffer.get(logitsData)
                            }
                        } // logitsResults closed
                        preNetOutputTensorForPostNet.close(); preNetOutputTensorForPostNet = null


                        // --- Log Softmax and Argmax (Simulate torch operations) ---
                        val (predToken, maxLogProb) = logSoftmaxArgmax(logitsData)

                        Log.d(TAG, "   t=$t, symbol=$symbolsAdded -> pred_token=$predToken (blank=${predToken == vocab.blankId}), max_logProb=%.4f".format(maxLogProb))

                        // --- Update Hypothesis and State ---
                        if (predToken == vocab.blankId) {
                            notBlank = false // Exit inner loop for this timestep 't'
                        } else {
                            hypothesis.add(predToken)
                            hState = newH // Update state only if not blank
                            cState = newC
                        }
                        symbolsAdded++

                    } finally {
                        // Ensure all intermediate tensors created in the inner loop are closed
                        targetTensor?.close(); targetTensor = null
                        targetLengthTensor?.close(); targetLengthTensor = null
                        hTensor?.close(); hTensor = null
                        cTensor?.close(); cTensor = null
                        gPredInputTensor?.close(); gPredInputTensor = null // Ensure closed if error occurred before end of try
                        combinedTensor?.close(); combinedTensor = null
                        preNetOutputTensorForPostNet?.close(); preNetOutputTensorForPostNet = null

                    }
                } // End inner while loop (symbols_added)
                fTensor?.close(); fTensor = null // Close fTensor after inner loop completes for timestep 't'

            } // End outer for loop (timesteps 't')

            Log.d(TAG, "RNN-T decoding finished.")
            return vocab.decode(hypothesis, language) // Use corrected vocab decode

        } catch (e: Exception) {
            Log.e(TAG, "Error during RNNT transcription", e)
            return "[RNNT Transcription Error: ${e.message}]"
        } finally {
            // Ensure all tensors potentially created outside the inner loop are closed
            jointEncInputTensor?.close()
            fTensor?.close() // Might still be open if loop exited early
        }
    }


    /**
     * Performs LogSoftmax and Argmax, similar to torch operations.
     * log_probs = torch.from_numpy(logits.astype(np.float32)).log_softmax(dim=-1)
     * pred_token = log_probs.argmax(dim=-1).item()
     */
    private fun logSoftmaxArgmax(logits: FloatArray): Pair<Int, Float> {
        if (logits.isEmpty()) {
            return Pair(-1, -Float.MAX_VALUE) // Error case
        }

        // Stabilize by subtracting max logit (log-sum-exp trick)
        val maxLogit = logits.maxOrNull() ?: 0.0f
        var sumExp = 0.0f
        for (logit in logits) {
            sumExp += exp(logit - maxLogit)
        }
        val logSumExp = ln(sumExp) + maxLogit // Add max back

        var predToken = 0
        var maxLogProb = -Float.MAX_VALUE

        for (i in logits.indices) {
            val logProb = logits[i] - logSumExp // Calculate log probability
            if (logProb > maxLogProb) {
                maxLogProb = logProb
                predToken = i
            }
        }
        return Pair(predToken, maxLogProb)
    }


    // --- (transcribeCtc - Keep existing implementation) ---
    suspend fun transcribeCtc(filePath: String, language: String): String { /* ... Keep implementation ... */
        if (!ensureCtcModelsLoaded()) return "[Error: CTC Models could not be loaded]"

        // 1. Run Encoder (which now uses the correct preprocessor and returns Long length)
        val encoderResult = runEncoder(filePath) ?: return "[Encoder Failed]"
        val (encoderOutputs, encoderShape, encodedLengthsLong) = encoderResult
        if (encodedLengthsLong == null || encodedLengthsLong.isEmpty()) {
            Log.e(TAG, "Encoder did not return valid output lengths for CTC.")
            return "[Encoder Lengths Error]"
        }
        val encodedLength = encodedLengthsLong[0].toInt() // Use the length

        // 2. Run CTC Decoder
        val vocabList = vocab.getVocabFor(language) ?: return "[Error: No vocab for '$language']"

        // Feed encoder output into CTC Decoder model
        // Input shape should match encoder output shape: [1, Features, Time]
        if (encoderShape.size != 3 || encoderShape[0] != 1L) {
            Log.e(TAG,"Unexpected encoder output shape for CTC Decoder: ${encoderShape.contentToString()}")
            return "[Error: Unexpected encoder shape]"
        }
        val ctcDecoderInputTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(encoderOutputs), encoderShape)
        Log.d(TAG, "Running CTC Decoder model...")
        val logits: FloatArray
        val timeSteps: Int
        val modelVocabSize: Int // Get vocab size from model output, not vocab.json

        try {
            // Input name should be "encoder_output" or similar, check your ctc_decoder.onnx
            ctcDecoderSession!!.run(Collections.singletonMap("encoder_output", ctcDecoderInputTensor)).use { results ->
                (results[0] as OnnxTensor).use { logitsTensor ->
                    val logitsShape = logitsTensor.info.shape
                    Log.d(TAG,"CTC Decoder output shape: ${logitsShape.contentToString()}")

                    // Expected shape [1, Time, VocabSize]
                    if (logitsShape.size != 3 || logitsShape[0] != 1L) {
                        Log.e(TAG,"CTC Decoder output shape has wrong dimensions: ${logitsShape.contentToString()}")
                        return "[Error: CTC Decoder output dimension mismatch]"
                    }

                    timeSteps = logitsShape[1].toInt()
                    modelVocabSize = logitsShape[2].toInt() // Size from the model output itself

                    // Verify timeSteps match encodedLength from encoder
                    if(timeSteps != encodedLength) {
                        Log.w(TAG,"CTC time steps ($timeSteps) != encoder length ($encodedLength). Using $timeSteps.")
                        // This might indicate a model mismatch or preprocessing issue if significantly different
                    }

                    val logitsBuffer = logitsTensor.floatBuffer
                    logits = FloatArray(logitsBuffer.remaining())
                    logitsBuffer.get(logits)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CTC Decoder run failed", e)
            return "[Error: CTC Decoder inference failed]"
        } finally {
            ctcDecoderInputTensor.close()
        }

        // 3. CTC Greedy Decode
        Log.d(TAG, "Running CTC Greedy Decode on logits (T=$timeSteps, ModelV=$modelVocabSize)...")
        val decodedIds = (0 until timeSteps).map { t ->
            val offset = t * modelVocabSize
            var maxIdx = 0
            var maxVal = -Float.MAX_VALUE
            for (v in 0 until modelVocabSize) {
                val currentLogit = logits.getOrElse(offset + v) { -Float.MAX_VALUE } // Safe indexing
                if (currentLogit > maxVal) {
                    maxVal = currentLogit
                    maxIdx = v
                }
            }
            maxIdx
        }

        // 4. Merge repeats and remove blanks (using vocab.blankId)
        val mergedIds = mutableListOf<Int>()
        var lastId = -1 // Use a value guaranteed not to be a valid ID
        decodedIds.forEach { id ->
            // Check against vocab.blankId (which should be 0 now for ai4bharat)
            if (id != lastId) {
                if (id != vocab.blankId) { // Also remove blank tokens here after merging repeats
                    mergedIds.add(id)
                }
                lastId = id
            }
        }

        // 5. Decode with IndicVocab (using language-specific subset)
        return vocab.decode(mergedIds, language) // Uses blankId=0, sosId=1 etc.
    }

    // --- (close - Close all sessions) ---
    fun close() {
        Log.d(TAG, "Closing ASRService models...")
        synchronized(loadingLock) {
            try { encoderSession?.close() } catch (e: Exception) { Log.e(TAG, "Error closing encoderSession", e)}
            try { rnntDecoderSession?.close() } catch (e: Exception) { Log.e(TAG, "Error closing rnntDecoderSession", e)}
            try { jointEncSession?.close() } catch (e: Exception) { Log.e(TAG, "Error closing jointEncSession", e)}
            try { jointPredSession?.close() } catch (e: Exception) { Log.e(TAG, "Error closing jointPredSession", e)}
            try { jointPreNetSession?.close() } catch (e: Exception) { Log.e(TAG, "Error closing jointPreNetSession", e)}
            try { ctcDecoderSession?.close() } catch (e: Exception) { Log.e(TAG, "Error closing ctcDecoderSession", e)}
            postNetSessions.values.forEach {
                try { it?.close() } catch (e: Exception) { Log.e(TAG, "Error closing a postNetSession", e)}
            }
            postNetSessions.clear()

            encoderSession = null
            rnntDecoderSession = null
            jointEncSession = null
            jointPredSession = null
            jointPreNetSession = null
            ctcDecoderSession = null

            areRnntModelsLoaded = false
            isCtcDecoderLoaded = false
        }
        Log.d(TAG, "ASRService models closed.")
    }
}