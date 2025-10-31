package com.example.allrecorder

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileInputStream
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
import kotlin.math.max

class AsrService(private val context: Context) {

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var preprocessorModule: Module? = null // For preprocessor.ts
    val vocab: IndicVocab

    // --- Model Sessions ---
    private var encoderSession: OrtSession? = null
    private var rnntDecoderSession: OrtSession? = null
    private var jointEncSession: OrtSession? = null
    private var jointPredSession: OrtSession? = null
    private var jointPreNetSession: OrtSession? = null
    private val postNetSessions = mutableMapOf<String, OrtSession?>()
    private var ctcDecoderSession: OrtSession? = null

    // --- State ---
    private var areRnntModelsLoaded = false
    private var isCtcDecoderLoaded = false
    private val loadingLock = Any()

    // --- Constants (Match Python Script) ---
    private val rnntMaxSymbolsPerStep = 10
    private val predRnnLayers = 2
    private val predRnnHiddenDim = 640
    private val TARGET_SAMPLE_RATE = 16000

    // --- START OF CHUNKING CONSTANTS ---
    // Process 15 seconds of *new* audio at a time
    private val MAIN_CHUNK_DURATION_IN_SECONDS = 15
    private val MAIN_CHUNK_IN_SAMPLES = TARGET_SAMPLE_RATE * MAIN_CHUNK_DURATION_IN_SECONDS

    // Add 2 seconds of *previous* audio as context
    private val CHUNK_OVERLAP_IN_SECONDS = 2
    private val CHUNK_OVERLAP_IN_SAMPLES = TARGET_SAMPLE_RATE * CHUNK_OVERLAP_IN_SECONDS
    // --- END OF CHUNKING CONSTANTS ---

    companion object {
        private const val TAG = "AsrService"
    }

    init {
        vocab = IndicVocab(context)
        Log.d(TAG, "AsrService initialized.")
    }

    @Synchronized
    private fun ensureRnntModelsLoaded(): Boolean {
        synchronized(loadingLock) {
            if (areRnntModelsLoaded && !areAnyRnntSessionsClosed() && preprocessorModule != null) return true
            Log.i(TAG, "Starting lazy loading of RNN-T ONNX models and PyTorch Preprocessor...")
            try {
                // --- MODIFIED: Load PyTorch Preprocessor (Full Module) ---
                if (preprocessorModule == null) {
                    val preprocessorPath = copyAssetToFile("indic_model/preprocessor.ts").absolutePath
                    preprocessorModule = Module.load(preprocessorPath)
                    Log.d(TAG, "Loaded PyTorch preprocessor.ts")
                }
                // --- END MODIFICATION ---

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

                areRnntModelsLoaded = !areAnyRnntSessionsClosed() && preprocessorModule != null
                if (areRnntModelsLoaded) {
                    Log.i(TAG, "RNN-T models and Preprocessor loaded successfully.")
                } else {
                    Log.e(TAG, "One or more RNN-T models or Preprocessor failed to load.")
                    closeRnntSessions()
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
            if (isCtcDecoderLoaded && !encoderSession.isClosedOrNull() && !ctcDecoderSession.isClosedOrNull() && preprocessorModule != null) { return true }
            Log.i(TAG, "Starting lazy loading of CTC ONNX models and PyTorch Preprocessor...")
            try {
                // --- MODIFIED: Load PyTorch Preprocessor (Full Module) ---
                if (preprocessorModule == null) {
                    val preprocessorPath = copyAssetToFile("indic_model/preprocessor.ts").absolutePath
                    preprocessorModule = Module.load(preprocessorPath)
                    Log.d(TAG, "Loaded PyTorch preprocessor.ts")
                }
                // --- END MODIFICATION ---

                val encoderFileName = "indic_model/encoder.quant.int8.onnx" // Assume quantized
                if (encoderSession.isClosedOrNull()) {
                    encoderSession = createSessionInternal(encoderFileName)
                }
                if (ctcDecoderSession.isClosedOrNull()) {
                    ctcDecoderSession = createSessionInternal("indic_model/ctc_decoder.onnx")
                }

                isCtcDecoderLoaded = !encoderSession.isClosedOrNull() && !ctcDecoderSession.isClosedOrNull() && preprocessorModule != null
                if (isCtcDecoderLoaded) {
                    Log.i(TAG, "CTC models and Preprocessor loaded successfully.")
                } else {
                    Log.e(TAG, "Encoder, CTC Decoder, or Preprocessor failed to load.")
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
        // preprocessorModule is shared, don't close here
    }
    // Helper function to close only CTC related sessions
    private fun closeCtcSessions() {
        ctcDecoderSession?.close(); ctcDecoderSession = null
        // preprocessorModule is shared, don't close here
    }

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
        return this == null
    }


    // --- MODIFIED: `readAudioFile` now reads raw .wav files ---
    private fun readAudioFile(filePath: String): FloatArray {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "Audio file not found: $filePath")
            return FloatArray(0)
        }

        try {
            FileInputStream(file).use { fileStream ->
                // Read the WAV header (44 bytes) and discard it
                val header = ByteArray(44)
                val readHeader = fileStream.read(header, 0, 44)
                if (readHeader < 44) {
                    Log.e(TAG, "Failed to read WAV header. File is too small.")
                    return FloatArray(0)
                }

                // Read the rest of the file (PCM data)
                val dataBytes = fileStream.readBytes()
                if (dataBytes.isEmpty()) {
                    Log.e(TAG, "WAV file contains no PCM data.")
                    return FloatArray(0)
                }

                // Wrap the byte array in a ByteBuffer to read 16-bit (2-byte) shorts
                val buffer = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                val numSamples = dataBytes.size / 2
                val floatArray = FloatArray(numSamples)

                for (i in 0 until numSamples) {
                    // Get the 16-bit short sample and normalize it to [-1.0, 1.0]
                    floatArray[i] = buffer.short.toFloat() / 32768.0f
                }

                Log.d(TAG, "Read ${floatArray.size} samples from WAV file.")
                return floatArray
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read WAV file: $filePath", e)
            return FloatArray(0)
        }
    }

    // This function is no longer used, but we'll keep it as a stub.
    private fun resampleLinear(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        Log.w(TAG, "resampleLinear called, but audio should already be 16kHz.")
        if (inputRate == outputRate) return input
        return input // Just return the original array
    }

    private fun transposeFloatArray(data: FloatArray, shape: LongArray, permutation: IntArray): Pair<FloatArray, LongArray> {
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
            throw IllegalArgumentException("New shape calculation error.")
        }


        val newData = FloatArray(data.size)
        var newIndex = 0

        for (i in 0 until nd1) {
            for (j in 0 until nd2) {
                for (k in 0 until nd3) {
                    val originalIndices = IntArray(3)
                    originalIndices[p1] = i
                    originalIndices[p2] = j
                    originalIndices[p3] = k
                    val (o1, o2, o3) = originalIndices

                    val originalIndex = o1 * (d2 * d3) + o2 * d3 + o3

                    if (originalIndex >= 0 && originalIndex < data.size) {
                        newData[newIndex++] = data[originalIndex]
                    } else {
                        Log.e(TAG, "Transpose index calculation error: $originalIndex out of bounds (size ${data.size}) for new index ($i, $j, $k)")
                        newData[newIndex++] = 0f
                    }
                }
            }
        }

        if (newIndex != data.size) {
            Log.w(TAG, "Transpose output size mismatch: expected ${data.size}, got $newIndex. Shape: ${shape.contentToString()}, NewShape: ${newShape.contentToString()}")
        }

        return Pair(newData, newShape)
    }


    // --- MODIFIED: runEncoder now takes a FloatArray (a chunk) instead of a filePath ---
    private fun runEncoder(audioFloats: FloatArray): Triple<FloatArray, LongArray, LongArray?>? {
        if (audioFloats.isEmpty()) {
            Log.e(TAG, "Audio chunk is empty.")
            return null
        }

        Log.d(TAG, "Running PyTorch Preprocessor on chunk...")
        val melSpectrogramFloats: FloatArray
        val melSpectrogramShape: LongArray
        val outputNumFrames: Long

        var audioTensor: Tensor? = null
        var pyLengthTensor: Tensor? = null
        var melTensor: OnnxTensor? = null
        var onnxLengthTensor: OnnxTensor? = null
        var result: Triple<FloatArray, LongArray, LongArray?>? = null

        try {
            // 1. Prepare PyTorch Preprocessor Inputs
            audioTensor = Tensor.fromBlob(audioFloats, longArrayOf(1, audioFloats.size.toLong()))
            pyLengthTensor = Tensor.fromBlob(longArrayOf(audioFloats.size.toLong()), longArrayOf(1))

            Log.v(TAG,"Running preprocessor.forward()...")
            // 2. Run PyTorch Preprocessor
            val preprocessorResult = preprocessorModule!!.forward(
                IValue.from(audioTensor),
                IValue.from(pyLengthTensor)
            )

            // 3. Unpack PyTorch Preprocessor Outputs
            val tuple = preprocessorResult.toTuple()
            val signalTensor = tuple[0].toTensor()
            val lengthTensorOut = tuple[1].toTensor()

            melSpectrogramFloats = signalTensor.dataAsFloatArray
            melSpectrogramShape = signalTensor.shape()
            val outputNumFramesLong = lengthTensorOut.dataAsLongArray
            outputNumFrames = outputNumFramesLong[0]

            Log.v(TAG,"Preprocessor output size: ${melSpectrogramFloats.size}, Shape: ${melSpectrogramShape.contentToString()}, NumFrames: $outputNumFrames")

            if (outputNumFrames == 0L || melSpectrogramFloats.isEmpty()) {
                Log.e(TAG, "Preprocessing error: 0 frames generated for chunk.")
                return null
            }

            // 4. Prepare ONNX Encoder Inputs (from PyTorch outputs)
            melTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(melSpectrogramFloats), melSpectrogramShape)
            onnxLengthTensor = OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(outputNumFramesLong), longArrayOf(1))

            Log.d(TAG, "Running ONNX encoder model on chunk...")
            val encoderInputs = mapOf(
                "audio_signal" to melTensor,
                "length" to onnxLengthTensor
            )

            // 5. Run ONNX Encoder
            encoderSession!!.run(encoderInputs).use { results ->
                Log.d(TAG,"Encoder run successful for chunk.")
                val outputsTensor = results.get("outputs").get() as OnnxTensor
                val lengthsTensor = results.get("encoded_lengths").get() as OnnxTensor

                val outputsArray = outputsTensor.floatBuffer.array()
                val outputShape = outputsTensor.info.shape
                val lengthsBuffer = lengthsTensor.longBuffer
                val lengthsArray = LongArray(lengthsBuffer.remaining())
                lengthsBuffer.get(lengthsArray)

                result = Triple(outputsArray, outputShape, lengthsArray)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder run (including preprocessing) failed for chunk", e)
            result = null
        } finally {
            // Clean up all tensors
//            audioTensor?.destroy()
//            pyLengthTensor?.destroy()
            melTensor?.close()
            onnxLengthTensor?.close()
        }

        return result
    }
    // --- END MODIFICATION ---


    /**
     * Replicates the RNNT inference logic from simple_inference.py
     * WARNING: This function has NOT been refactored for chunking and will
     * OOM on large files. It is kept here for completeness but should not
     * be used on long audio until it is properly statefully refactored.
     * * CURRENT BEHAVIOR: Processes FIRST CHUNK ONLY.
     */
    suspend fun transcribeRnnt(filePath: String, language: String): String {
        Log.w(TAG, "RNNT transcription for large files is not fully implemented. Processing FIRST CHUNK (15s) ONLY.")
        if (!ensureRnntModelsLoaded()) {
            return "[Error: RNN-T Models could not be loaded]"
        }
        val postNet = getPostNetSessionInternal(language)
        if (postNet == null) {
            return "[Error: Post-net model for '$language' not found or failed to load]"
        }

        // 1. Run Encoder on FIRST CHUNK
        val fullAudio = readAudioFile(filePath)
        if (fullAudio.isEmpty()) return "[Error: Audio file empty or unreadable]"

        val chunk = fullAudio.sliceArray(0 until min(fullAudio.size, MAIN_CHUNK_IN_SAMPLES))
        val encoderResult = runEncoder(chunk)

        if (encoderResult == null) {
            return "[Encoder Failed on first chunk]"
        }

        val (encoderOutputs, encoderShape, encodedLengthsLong) = encoderResult
        if (encodedLengthsLong == null || encodedLengthsLong.isEmpty()) {
            Log.e(TAG, "Encoder did not return valid output lengths.")
            return "[Encoder Lengths Error]"
        }
        val encodedLength = encodedLengthsLong[0].toInt()
        Log.d(TAG, "Encoder output shape: ${encoderShape.contentToString()}, Effective length: $encodedLength")


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
            val (encoderTransposedData, encoderTransposedShape) = transposeFloatArray(
                encoderOutputs, encoderShape, intArrayOf(0, 2, 1) // Permutation: Batch, Time, Features
            )
            Log.d(TAG, "Transposed encoder output shape: ${encoderTransposedShape.contentToString()}")

            jointEncInputTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(encoderTransposedData), encoderTransposedShape)

            // 3. Run Joint Encoder (joint_enc.onnx) -> Produces 'f_base'
            Log.d(TAG, "Running Joint Encoder...")
            val f_base: FloatArray
            jointEncSession!!.run(Collections.singletonMap("input", jointEncInputTensor)).use { results ->
                (results[0] as OnnxTensor).use { outputTensor ->
                    Log.d(TAG, "Joint Encoder output shape: ${outputTensor.info.shape.contentToString()}")
                    val buffer = outputTensor.floatBuffer
                    f_base = FloatArray(buffer.remaining())
                    buffer.get(f_base)
                }
            }
            jointEncInputTensor.close(); jointEncInputTensor = null

            val jointHiddenDim = if (encoderTransposedShape[1] > 0) f_base.size / encoderTransposedShape[1].toInt() else 0
            if(jointHiddenDim != predRnnHiddenDim) {
                Log.w(TAG,"Potential mismatch: Joint Encoder output dim ($jointHiddenDim) != PRED_RNN_HIDDEN_DIM ($predRnnHiddenDim)")
            }
            Log.d(TAG,"Joint Encoder output processed. Shape [1, ${encoderTransposedShape[1]}, $jointHiddenDim]")


            // 4. RNNT Decode Loop Initialization
            Log.d(TAG, "Initializing RNNT decode loop state...")
            val hypothesis = mutableListOf(vocab.sosId)
            var hState = FloatArray(predRnnLayers * 1 * predRnnHiddenDim) { 0f }
            var cState = FloatArray(predRnnLayers * 1 * predRnnHiddenDim) { 0f }
            val stateShape = longArrayOf(predRnnLayers.toLong(), 1, predRnnHiddenDim.toLong())

            val fFrame = FloatArray(predRnnHiddenDim)
            val actualFramesInFBase = if (predRnnHiddenDim > 0) f_base.size / predRnnHiddenDim else 0

            val loopLimit = min(encodedLength, actualFramesInFBase)
            if (encodedLength > actualFramesInFBase) {
                Log.w(TAG, "Encoder reported length ($encodedLength) > actual frames in f_base ($actualFramesInFBase). Using $actualFramesInFBase.")
            } else if (encodedLength < actualFramesInFBase) {
                Log.w(TAG,"Encoder reported length ($encodedLength) < actual frames in f_base ($actualFramesInFBase). Using $encodedLength.")
            }

            Log.d(TAG, "Starting RNNT decode loop for $loopLimit frames...")

            // 5. Main Decoding Loop (Outer loop over time 't')
            for (t in 0 until loopLimit) {
                try {
                    System.arraycopy(f_base, t * predRnnHiddenDim, fFrame, 0, predRnnHiddenDim)
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying f_base frame at t=$t", e)
                    return "[Error: Array copy failed in decode loop]"
                }
                fTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(fFrame), longArrayOf(1, 1, predRnnHiddenDim.toLong()))

                var symbolsAdded = 0
                var notBlank = true

                // Inner loop (while predicted token is not blank)
                while (notBlank && symbolsAdded < rnntMaxSymbolsPerStep) {
                    try {
                        val lastTokenInt = hypothesis.last()
                        targetTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(intArrayOf(lastTokenInt)), longArrayOf(1, 1))
                        targetLengthTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1))
                        hTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(hState), stateShape)
                        cTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(cState), stateShape)

                        val decoderInputs = mapOf(
                            "targets" to targetTensor,
                            "target_length" to targetLengthTensor,
                            "states.1" to hTensor,
                            "onnx::Slice_3" to cTensor
                        )

                        Log.v(TAG, "t=$t, symbol=$symbolsAdded: Running RNNT Decoder...")
                        val newH: FloatArray
                        val newC: FloatArray
                        val gOutputData: FloatArray
                        val gOutputShape: LongArray

                        rnntDecoderSession!!.run(decoderInputs).use { decoderResults ->
                            val gOutTensor = decoderResults.get("outputs").get() as OnnxTensor
                            val hOutTensor = decoderResults.get("states").get() as OnnxTensor
                            val cOutTensor = decoderResults.get("162").get() as OnnxTensor

                            Log.v(TAG, "   Decoder shapes: g=${gOutTensor.info.shape.contentToString()}, h=${hOutTensor.info.shape.contentToString()}, c=${cOutTensor.info.shape.contentToString()}")

                            newH = hOutTensor.floatBuffer.array()
                            newC = cOutTensor.floatBuffer.array()
                            gOutputShape = gOutTensor.info.shape
                            val gBuffer = gOutTensor.floatBuffer
                            gOutputData = FloatArray(gBuffer.remaining())
                            gBuffer.get(gOutputData)
                        } // decoderResults closed

                        Log.v(TAG, "   Running Joint Prediction...")
                        val (gTransposedData, gTransposedShape) = transposeFloatArray(gOutputData, gOutputShape, intArrayOf(0, 2, 1))
                        gPredInputTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(gTransposedData), gTransposedShape)

                        val gFloats: FloatArray
                        jointPredSession!!.run(Collections.singletonMap("input", gPredInputTensor)).use { gResults ->
                            (gResults[0] as OnnxTensor).use { gTemp ->
                                Log.v(TAG, "   Joint Pred output shape: ${gTemp.info.shape.contentToString()}")
                                val gBuffer = gTemp.floatBuffer
                                gFloats = FloatArray(gBuffer.remaining())
                                gBuffer.get(gFloats)
                            }
                        }
                        gPredInputTensor.close(); gPredInputTensor = null

                        if (fFrame.size != gFloats.size) {
                            Log.e(TAG,"Shape mismatch before combine: fFrame=${fFrame.size}, gFloats=${gFloats.size}")
                            return "[Error: F/G shape mismatch]"
                        }
                        val combinedData = FloatArray(predRnnHiddenDim) { i -> fFrame[i] + gFloats[i] }
                        combinedTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(combinedData), longArrayOf(1, 1, predRnnHiddenDim.toLong()))

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
                        }
                        combinedTensor.close(); combinedTensor = null
                        preNetInputTensor = OnnxTensor.createTensor(ortEnvironment,FloatBuffer.wrap(preNetOutData),preNetOutShape)

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
                        }
                        preNetOutputTensorForPostNet.close(); preNetOutputTensorForPostNet = null


                        val (predToken, maxLogProb) = logSoftmaxArgmax(logitsData)
                        Log.d(TAG, "   t=$t, symbol=$symbolsAdded -> pred_token=$predToken (blank=${predToken == vocab.blankId}), max_logProb=%.4f".format(maxLogProb))

                        if (predToken == vocab.blankId) {
                            notBlank = false
                        } else {
                            hypothesis.add(predToken)
                            hState = newH
                            cState = newC
                        }
                        symbolsAdded++

                    } finally {
                        targetTensor?.close(); targetTensor = null
                        targetLengthTensor?.close(); targetLengthTensor = null
                        hTensor?.close(); hTensor = null
                        cTensor?.close(); cTensor = null
                        gPredInputTensor?.close(); gPredInputTensor = null
                        combinedTensor?.close(); combinedTensor = null
                        preNetOutputTensorForPostNet?.close(); preNetOutputTensorForPostNet = null
                    }
                } // End inner while
                fTensor?.close(); fTensor = null

            } // End outer for

            Log.d(TAG, "RNN-T decoding finished.")
            return "(First 15s only): " + vocab.decode(hypothesis, language)

        } catch (e: Exception) {
            Log.e(TAG, "Error during RNNT transcription", e)
            return "[RNNT Transcription Error: ${e.message}]"
        } finally {
            jointEncInputTensor?.close()
            fTensor?.close()
        }
    }


    private fun logSoftmaxArgmax(logits: FloatArray): Pair<Int, Float> {
        if (logits.isEmpty()) {
            return Pair(-1, -Float.MAX_VALUE)
        }

        val maxLogit = logits.maxOrNull() ?: 0.0f
        var sumExp = 0.0f
        for (logit in logits) {
            sumExp += exp(logit - maxLogit)
        }
        val logSumExp = ln(sumExp) + maxLogit

        var predToken = 0
        var maxLogProb = -Float.MAX_VALUE

        for (i in logits.indices) {
            val logProb = logits[i] - logSumExp
            if (logProb > maxLogProb) {
                maxLogProb = logProb
                predToken = i
            }
        }
        return Pair(predToken, maxLogProb)
    }


    // --- MODIFIED: `transcribeCtc` now implements the overlapping window ---
    suspend fun transcribeCtc(filePath: String, language: String): String {
        if (!ensureCtcModelsLoaded()) return "[Error: CTC Models could not be loaded]"

        // 1. Load the full audio file ONCE.
        val fullAudio = readAudioFile(filePath)
        if (fullAudio.isEmpty()) return "[Error: Audio file empty or unreadable]"

        val finalTranscript = StringBuilder()
        var currentPosition = 0 // This marks the start of the *new* audio

        Log.i(TAG, "Starting CTC chunked transcription... File samples: ${fullAudio.size}")

        while (currentPosition < fullAudio.size) {

            // --- START OF OVERLAP CHUNK LOGIC ---
            // Start of the chunk (include overlap)
            val chunkStart = max(0, currentPosition - CHUNK_OVERLAP_IN_SAMPLES)
            // End of the chunk
            val chunkEnd = min(fullAudio.size, currentPosition + MAIN_CHUNK_IN_SAMPLES)

            // Get the audio slice
            val chunk = fullAudio.sliceArray(chunkStart until chunkEnd)

            Log.d(TAG, "Processing chunk: samples $chunkStart to $chunkEnd")

            // Check if the chunk is just leftover overlap from the end
            if (chunk.size < CHUNK_OVERLAP_IN_SAMPLES && currentPosition > 0) {
                Log.d(TAG, "Skipping final chunk (it's all overlap).")
                break
            }
            // --- END OF OVERLAP CHUNK LOGIC ---

            // 2. Run Encoder on the CHUNK
            val encoderResult = runEncoder(chunk)
            if (encoderResult == null) {
                Log.e(TAG, "Encoder failed for chunk at $currentPosition")
                currentPosition += MAIN_CHUNK_IN_SAMPLES // Skip this chunk
                continue
            }

            val (encoderOutputs, encoderShape, encodedLengthsLong) = encoderResult
            if (encodedLengthsLong == null || encodedLengthsLong.isEmpty()) {
                Log.e(TAG, "Encoder did not return valid output lengths for CTC chunk.")
                currentPosition += MAIN_CHUNK_IN_SAMPLES
                continue
            }
            val encodedLength = encodedLengthsLong[0].toInt()

            // 3. Get Language Mask (same for all chunks)
            val maskIndices = vocab.getMaskFor(language)
            if (maskIndices == null) {
                Log.e(TAG, "CTC Error: No language_mask found for '$language'")
                return "[Error: No language mask for '$language']"
            }

            // 4. Run CTC Decoder on the CHUNK's encoder output
            val chunkTranscript = runCtcDecoder(encoderOutputs, encoderShape, encodedLength, maskIndices, language)

            // 5. Append transcript and advance
            // NOTE: This simple implementation will have repeated words at the seams.
            finalTranscript.append(chunkTranscript).append(" ")
            currentPosition += MAIN_CHUNK_IN_SAMPLES

            // Yield to prevent blocking the thread entirely
            kotlinx.coroutines.yield()
        }

        Log.i(TAG, "Chunked transcription finished.")
        return finalTranscript.toString().trim()
    }

    // --- NEW: Extracted CTC Decoder logic into its own function ---
    private fun runCtcDecoder(
        encoderOutputs: FloatArray,
        encoderShape: LongArray,
        encodedLength: Int,
        maskIndices: List<Int>,
        language: String
    ): String {

        var ctcDecoderInputTensor: OnnxTensor? = null
        try {
            if (encoderShape.size != 3 || encoderShape[0] != 1L) {
                Log.e(TAG,"Unexpected encoder output shape for CTC Decoder: ${encoderShape.contentToString()}")
                return ""
            }
            ctcDecoderInputTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(encoderOutputs), encoderShape)
            Log.d(TAG, "Running CTC Decoder model on chunk...")
            val globalLogits: FloatArray
            val timeSteps: Int
            val globalVocabSize: Int

            ctcDecoderSession!!.run(Collections.singletonMap("encoder_output", ctcDecoderInputTensor)).use { results ->
                (results[0] as OnnxTensor).use { logitsTensor ->
                    val logitsShape = logitsTensor.info.shape
                    timeSteps = logitsShape[1].toInt()
                    globalVocabSize = logitsShape[2].toInt()

                    if(timeSteps != encodedLength) {
                        Log.w(TAG,"CTC time steps ($timeSteps) != encoder length ($encodedLength). Using $timeSteps.")
                    }

                    val logitsBuffer = logitsTensor.floatBuffer
                    globalLogits = FloatArray(logitsBuffer.remaining())
                    logitsBuffer.get(globalLogits)
                }
            }

            // 3. Greedy Decode using MASK
            val decodedMaskIndices = IntArray(timeSteps)
            for (t in 0 until timeSteps) {
                val offset = t * globalVocabSize
                var bestMaskIndex = 0
                var maxLogit = -Float.MAX_VALUE

                // Iterate only over the indices specified in the mask
                for (maskIndex in maskIndices.indices) { // 0 to langVocabSize-1
                    val globalIndex = maskIndices[maskIndex] // Get the index into the global logits
                    if (globalIndex < 0 || globalIndex >= globalVocabSize) {
                        // Log.e(TAG, "Mask index $globalIndex out of bounds for global vocab size $globalVocabSize")
                        continue
                    }
                    val logit = globalLogits[offset + globalIndex]
                    if (logit > maxLogit) {
                        maxLogit = logit
                        bestMaskIndex = maskIndex // Store the index *of the mask*
                    }
                }
                decodedMaskIndices[t] = bestMaskIndex
            }

            // 4. Merge repeats
            val mergedIndices = mutableListOf<Int>()
            var lastId = -1
            decodedMaskIndices.forEach { id ->
                if (id != lastId) {
                    mergedIndices.add(id)
                    lastId = id
                }
            }

            // 5. Remove blank
            val finalIds = mergedIndices.filter { it != vocab.blankId }

            // 6. Decode
            return vocab.decode(finalIds, language)

        } catch (e: Exception) {
            Log.e(TAG, "CTC Decoder run failed", e)
            return "[Error: CTC chunk failed]"
        } finally {
            ctcDecoderInputTensor?.close()
        }
    }


    // --- (close - Close all sessions) ---
    fun close() {
        Log.d(TAG, "Closing ASRService models...")
        synchronized(loadingLock) {
            try { preprocessorModule?.destroy() } catch (e: Exception) { Log.e(TAG, "Error closing preprocessorModule", e)}
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

            preprocessorModule = null
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