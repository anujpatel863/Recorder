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
import java.io.InputStream
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
class AsrService(private val context: Context) {

    // ... (ortEnvironment, preprocessor, vocab, session variables, state, constants remain the same) ...
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    val preprocessor: AudioPreprocessor
    val vocab: IndicVocab

    // --- Model Sessions - Initialized to null for lazy loading ---
    private var encoderSession: OrtSession? = null
    private var rnntDecoderSession: OrtSession? = null
    private var jointEncSession: OrtSession? = null
    private var jointPredSession: OrtSession? = null
    private var jointPreNetSession: OrtSession? = null
    private val postNetSessions = mutableMapOf<String, OrtSession?>() // Also nullable
    private var ctcDecoderSession: OrtSession? = null

    // --- State for lazy loading ---
    private var areRnntModelsLoaded = false
    private var isCtcDecoderLoaded = false
    private val loadingLock = Any() // To prevent multiple threads loading simultaneously

    // --- Constants ---
    private val rnntMaxSymbolsPerStep = 10
    private val predRnnLayers = 2
    private val predRnnHiddenDim = 640
    private val TARGET_SAMPLE_RATE = 16000

    companion object {
        private const val TAG = "AsrService"
    }


    init {
        preprocessor = AudioPreprocessor(context)
        vocab = IndicVocab(context)
        Log.d(TAG, "AsrService initialized (models will load lazily).")
    }

    // --- (Lazy Loading Logic, createSessionInternal, copyAssetToFile, getPostNetSessionInternal, isClosedOrNull remain the same) ---
    @Synchronized
    private fun ensureRnntModelsLoaded(): Boolean {
        synchronized(loadingLock) {
            if (areRnntModelsLoaded) {
                return !encoderSession.isClosedOrNull() &&
                        !rnntDecoderSession.isClosedOrNull() &&
                        !jointEncSession.isClosedOrNull() &&
                        !jointPredSession.isClosedOrNull() &&
                        !jointPreNetSession.isClosedOrNull()
            }

            Log.i(TAG, "Starting lazy loading of RNN-T ONNX models...")
            try {
                if (encoderSession.isClosedOrNull()) {
                    encoderSession = createSessionInternal("indic_model/encoder.quant.int8.onnx")
                }
                rnntDecoderSession = createSessionInternal("indic_model/rnnt_decoder.onnx")
                jointEncSession = createSessionInternal("indic_model/joint_enc.onnx")
                jointPredSession = createSessionInternal("indic_model/joint_pred.onnx")
                jointPreNetSession = createSessionInternal("indic_model/joint_pre_net.onnx")
                getPostNetSessionInternal("gu") // Load gujarati by default

                if (encoderSession.isClosedOrNull() || rnntDecoderSession.isClosedOrNull() ||
                    jointEncSession.isClosedOrNull() || jointPredSession.isClosedOrNull() ||
                    jointPreNetSession.isClosedOrNull()) {
                    Log.e(TAG, "One or more RNN-T models failed to load.")
                    close()
                    areRnntModelsLoaded = false
                    return false
                }
                areRnntModelsLoaded = true
                Log.i(TAG, "RNN-T models loaded successfully.")
                return true
            } catch (e: Exception) { Log.e(TAG, "Exception during RNN-T model loading", e); close(); areRnntModelsLoaded = false; return false }
        }
    }

    @Synchronized
    private fun ensureCtcModelsLoaded(): Boolean {
        synchronized(loadingLock) {
            if (isCtcDecoderLoaded && !encoderSession.isClosedOrNull()) { return true }
            Log.i(TAG, "Starting lazy loading of CTC ONNX models...")
            try {
                if (encoderSession.isClosedOrNull()) { encoderSession = createSessionInternal("indic_model/encoder.quant.int8.onnx") }
                if (ctcDecoderSession.isClosedOrNull()) { ctcDecoderSession = createSessionInternal("indic_model/ctc_decoder.onnx") }
                if (encoderSession.isClosedOrNull() || ctcDecoderSession.isClosedOrNull()) {
                    Log.e(TAG, "Encoder or CTC Decoder failed to load.")
                    close(); isCtcDecoderLoaded = false; return false
                }
                isCtcDecoderLoaded = true
                Log.i(TAG, "CTC models loaded successfully.")
                return true
            } catch (e: Exception) { Log.e(TAG, "Exception during CTC model loading", e); close(); isCtcDecoderLoaded = false; return false }
        }
    }

    private fun createSessionInternal(assetPath: String): OrtSession? {
        Log.d(TAG, "Attempting to load model by copying from asset path: $assetPath")
        val modelFile: File? = try { copyAssetToFile(assetPath) } catch (e: Exception) { Log.e(TAG, "Failed to copy asset $assetPath to cache", e); null }
        if (modelFile == null || !modelFile.exists()) { Log.e(TAG, "Model file is null or does not exist after copying."); return null }
        val dataAssetPath = "$assetPath.data"
        try {
            val parentDir = File(assetPath).parent ?: ""; val assetFileName = File(assetPath).name + ".data"
            val assetList = context.assets.list(parentDir)
            if (assetList != null && assetList.contains(assetFileName)) { copyAssetToFile(dataAssetPath); Log.d(TAG, "Copied associated .data file: $dataAssetPath") }
            else { Log.d(TAG, "No associated .data file found for $assetPath in assets/$parentDir") }
        } catch (e: Exception) { Log.w(TAG, "Could not check for or copy .data file $dataAssetPath", e) }
        Log.d(TAG, "Loading model from copied file path: ${modelFile.absolutePath}")
        return try { ortEnvironment.createSession(modelFile.absolutePath, SessionOptions()) }
        catch (e: Exception) { Log.e(TAG, "Failed to load model from file ${modelFile.absolutePath}", e); null }
    }

    @Throws(Exception::class)
    private fun copyAssetToFile(assetPath: String): File {
        val assetFileName = File(assetPath).name; val outFile = File(context.cacheDir, assetFileName)
        if (outFile.exists()) { outFile.delete() }
        context.assets.open(assetPath).use { i -> FileOutputStream(outFile).use { o -> i.copyTo(o) } }
        Log.d(TAG, "Copied asset '$assetPath' to '${outFile.absolutePath}'"); return outFile
    }

    private fun getPostNetSessionInternal(language: String): OrtSession? {
        if (!postNetSessions.containsKey(language) || postNetSessions[language].isClosedOrNull() ) {
            val session = createSessionInternal("indic_model/joint_post_net_$language.onnx")
            postNetSessions[language] = session
            if (session == null) { Log.e(TAG, "Failed to load post-net for language: $language") }
        }
        return postNetSessions[language]
    }

    private fun OrtSession?.isClosedOrNull(): Boolean { return this == null }


    // --- (readAudioFile, resampleLinear, transposeFloatArray remain the same) ---
    private fun readAudioFile(filePath: String): FloatArray {
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

            val buffer = ByteBuffer.allocate(1024 * 8).order(ByteOrder.nativeOrder())
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize <= 0) break
                buffer.rewind()
                while (buffer.remaining() >= 2) {
                    audioSamples.add(buffer.short.toFloat() / 32768.0f) // Normalize
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

        if (originalSampleRate != TARGET_SAMPLE_RATE && originalSampleRate > 0) {
            Log.d(TAG, "Resampling audio from $originalSampleRate Hz to $TARGET_SAMPLE_RATE Hz")
            return resampleLinear(audioSamples.toFloatArray(), originalSampleRate, TARGET_SAMPLE_RATE)
        } else if (originalSampleRate <= 0) {
            Log.e(TAG, "Could not determine original sample rate.")
            return FloatArray(0)
        } else {
            return audioSamples.toFloatArray()
        }
    }
    private fun resampleLinear(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        val inputLength = input.size
        val outputLength = (inputLength.toLong() * outputRate / inputRate).toInt()
        val output = FloatArray(outputLength)
        val step = inputRate.toDouble() / outputRate.toDouble()
        var currentInputIndex = 0.0

        for (i in 0 until outputLength) {
            val indexFloor = floor(currentInputIndex).toInt()
            val indexCeil = indexFloor + 1
            val fraction = currentInputIndex - indexFloor

            val valueFloor = if (indexFloor >= 0 && indexFloor < inputLength) input[indexFloor] else 0f
            val valueCeil = if (indexCeil >= 0 && indexCeil < inputLength) input[indexCeil] else 0f

            output[i] = (valueFloor * (1.0 - fraction) + valueCeil * fraction).toFloat()
            currentInputIndex += step
        }
        return output
    }
    private fun transposeFloatArray(data: FloatArray, shape: LongArray, permutation: IntArray): Pair<FloatArray, LongArray> {
        if (shape.size != 3 || permutation.size != 3) {
            throw IllegalArgumentException("Only 3D transpose is supported for this helper.")
        }
        val (d1, d2, d3) = shape.map { it.toInt() }
        val (p1, p2, p3) = permutation
        val newShape = longArrayOf(shape[p1], shape[p2], shape[p3])
        val (nd1, nd2, nd3) = newShape.map { it.toInt() }
        val newData = FloatArray(data.size)
        var newIndex = 0
        for (i in 0 until nd1) {
            for (j in 0 until nd2) {
                for (k in 0 until nd3) {
                    val originalIndices = IntArray(3)
                    originalIndices[p1] = i; originalIndices[p2] = j; originalIndices[p3] = k
                    val (o1, o2, o3) = originalIndices
                    val originalIndex = o1 * (d2 * d3) + o2 * d3 + o3
                    if (originalIndex >= 0 && originalIndex < data.size) {
                        newData[newIndex++] = data[originalIndex]
                    } else {
                        Log.e(TAG, "Transpose index $originalIndex out of bounds (size ${data.size}) for shape ${shape.contentToString()} perm ${permutation.contentToString()}")
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

    // --- Shared Encoder Function (remains the same) ---
    private fun runEncoder(filePath: String): Triple<FloatArray, LongArray, LongArray?>? {
        val audioFloats = readAudioFile(filePath)
        if (audioFloats.isEmpty()) {
            Log.e(TAG, "Audio read or resampling error")
            return null
        }
        val melSpectrogramFlat = preprocessor.process(audioFloats)
        val numFrames = if (preprocessor.nMels > 0) melSpectrogramFlat.size / preprocessor.nMels else 0
        if (numFrames == 0) { Log.e(TAG, "Preprocessing error"); return null }
        val shape = longArrayOf(1, preprocessor.nMels.toLong(), numFrames.toLong())
        val melTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(melSpectrogramFlat), shape)
        val lengthTensor = OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(longArrayOf(numFrames.toLong())), longArrayOf(1))
        Log.d(TAG, "Running encoder...")
        val encoderInputs = mapOf("audio_signal" to melTensor, "length" to lengthTensor)
        val result: Triple<FloatArray, LongArray, LongArray?>? = try {
            encoderSession!!.run(encoderInputs).use { results ->
                val outputsTensor = (results[0] as OnnxTensor)
                val outputsArray = outputsTensor.floatBuffer.array()
                val outputShape = outputsTensor.info.shape
                val lengthsArray = (results[1] as OnnxTensor).longBuffer.array()
                Triple(outputsArray, outputShape, lengthsArray)
            }
        } catch (e: Exception) { Log.e(TAG, "Encoder run failed", e); null }
        finally { melTensor.close(); lengthTensor.close() }
        if (result != null) { Log.d(TAG, "Encoder done. Output frames: ${result.third?.firstOrNull()}") }
        return result
    }

    suspend fun transcribeRnnt(filePath: String, language: String): String {
        if (!ensureRnntModelsLoaded()) return "[Error: RNNT Models could not be loaded]"
        val postNet = getPostNetSessionInternal(language) ?: return "[Error: Post-net model for '$language' not found or failed to load]"
        val encoderResult = runEncoder(filePath) ?: return "[Encoder Failed]"
        val (encoderOutputs, encoderShape, encodedLengths) = encoderResult
        val (encoderTransposedData, encoderTransposedShape) = transposeFloatArray(encoderOutputs, encoderShape, intArrayOf(0, 2, 1))
        val jointEncInTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(encoderTransposedData), encoderTransposedShape)

        val f_base: FloatArray
        jointEncSession!!.run(Collections.singletonMap("input", jointEncInTensor)).use { results ->
            (results[0] as OnnxTensor).use { outputTensor ->
                val buffer = outputTensor.floatBuffer; f_base = FloatArray(buffer.remaining()); buffer.get(f_base)
            }
        }
        jointEncInTensor.close()

        val hypothesis = mutableListOf(vocab.sosId)
        var hState = FloatArray(predRnnLayers * 1 * predRnnHiddenDim) { 0f }
        var cState = FloatArray(predRnnLayers * 1 * predRnnHiddenDim) { 0f }
        val hShape = longArrayOf(predRnnLayers.toLong(), 1, predRnnHiddenDim.toLong())
        val encoderReportedFrames = encodedLengths?.firstOrNull()?.toInt() ?: encoderShape[1].toInt()
        val fFrame = FloatArray(predRnnHiddenDim)
        val actualFramesInFBase = if (predRnnHiddenDim > 0) f_base.size / predRnnHiddenDim else 0
        val loopLimit = min(encoderReportedFrames, actualFramesInFBase)
        if (encoderReportedFrames != actualFramesInFBase) { Log.w(TAG, "Frame count mismatch!...") }
        Log.d(TAG, "Starting RNNT decode loop for $loopLimit frames...")
        for (t in 0 until loopLimit) {
            try { System.arraycopy(f_base, t * predRnnHiddenDim, fFrame, 0, predRnnHiddenDim) }
            catch (e: ArrayIndexOutOfBoundsException) { /* ... */ return "[Error: Array copy failed in decode loop]" }

            val fTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(fFrame), longArrayOf(1, 1, predRnnHiddenDim.toLong()))
            var symbolsAdded = 0; var notBlank = true
            while (notBlank && symbolsAdded < rnntMaxSymbolsPerStep) {
                var targetTensor: OnnxTensor? = null; var targetLengthTensor: OnnxTensor? = null
                var hTensor: OnnxTensor? = null; var cTensor: OnnxTensor? = null
                // Removed declarations for intermediate tensors managed by .use

                try {
                    val lastTokenInt = hypothesis.last()
                    targetTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(intArrayOf(lastTokenInt)), longArrayOf(1, 1))
                    targetLengthTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1))
                    hTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(hState), hShape)
                    cTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(cState), hShape)
                    val decoderInputs = mapOf("targets" to targetTensor, "target_length" to targetLengthTensor, "states.1" to hTensor, "onnx::Slice_3" to cTensor)

                    val newH: FloatArray; val newC: FloatArray
                    val gOutData: FloatArray

                    rnntDecoderSession!!.run(decoderInputs).use { decoderResults ->
                        newH = (decoderResults[2] as OnnxTensor).use { it.floatBuffer.array() }
                        newC = (decoderResults[3] as OnnxTensor).use { it.floatBuffer.array() }
                        (decoderResults[0] as OnnxTensor).use { gOutTemp -> gOutData = FloatArray(predRnnHiddenDim); gOutTemp.floatBuffer.get(gOutData) }
                    }

                    val (gTransposedData, gTransposedShape) = transposeFloatArray(gOutData, longArrayOf(1, predRnnHiddenDim.toLong(), 1), intArrayOf(0, 2, 1))

                    // --- FIX: Correctly chain .use blocks ---
                    val logits: FloatArray
                    var predToken = 0 // Initialize here
                    var maxProb = -Float.MAX_VALUE // Initialize here

                    OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(gTransposedData), gTransposedShape).use { jointPredInTensor ->
                        jointPredSession!!.run(Collections.singletonMap("input", jointPredInTensor)).use { gResults ->
                            (gResults[0] as OnnxTensor).use { gTemp ->
                                val gFloats = FloatArray(predRnnHiddenDim); gTemp.floatBuffer.get(gFloats)
                                val combinedData = FloatArray(predRnnHiddenDim) { i -> fFrame[i] + gFloats[i] }

                                OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(combinedData), longArrayOf(1, 1, predRnnHiddenDim.toLong())).use { combinedTensor ->
                                    jointPreNetSession!!.run(Collections.singletonMap("input", combinedTensor)).use { preNetResults ->
                                        (preNetResults[0] as OnnxTensor).use { preNetOutTemp ->
                                            val preNetOutShape = preNetOutTemp.info.shape
                                            val preNetOutData = FloatArray(preNetOutShape.fold(1L){acc, dim -> acc * dim}.toInt())
                                            preNetOutTemp.floatBuffer.get(preNetOutData)
                                            Log.d(TAG, "Shape of preNetOut: ${preNetOutShape.contentToString()}")

                                            OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(preNetOutData), preNetOutShape).use { preNetOutTensorForPostNet ->
                                                postNet.run(Collections.singletonMap("input", preNetOutTensorForPostNet)).use { logitsResults ->
                                                    (logitsResults[0] as OnnxTensor).use { logitsTensor ->
                                                        val shape = logitsTensor.info.shape
                                                        val size = shape.fold(1L) { acc, dim -> acc * dim }.toInt()
                                                        logits = FloatArray(size) // Assign logits here
                                                        logitsTensor.floatBuffer.get(logits)

                                                        // Argmax logic... (moved inside)
                                                        val maxLogit = logits.maxOrNull() ?: 0.0f
                                                        var sumExp = 0.0f
                                                        logits.forEachIndexed { i, logit -> logits[i] = logit - maxLogit; sumExp += exp(logits[i]) }
                                                        val logSumExp = ln(sumExp)
                                                        // Reset predToken/maxProb for this step
                                                        predToken = 0; maxProb = -Float.MAX_VALUE
                                                        logits.forEachIndexed { i, logit ->
                                                            val logProb = logit - logSumExp
                                                            // ... (probability logging) ...
                                                            if (logProb > maxProb) { maxProb = logProb; predToken = i }
                                                        }
                                                    } // logitsTensor closed
                                                } // logitsResults closed
                                            } // preNetOutTensorForPostNet closed
                                        } // preNetOutTemp closed
                                    } // preNetResults closed
                                } // combinedTensor closed
                            } // gTemp closed
                        } // gResults closed
                    } // jointPredInTensor closed
                    // --- End Fix ---


                    Log.d(TAG, "t=$t, symbol=$symbolsAdded, pred_token=$predToken (blank=${predToken == vocab.blankId}), max_logProb=%.4f".format(maxProb))

                    if (predToken == vocab.blankId) { notBlank = false }
                    else { hypothesis.add(predToken); hState = newH; cState = newC }
                    symbolsAdded++
                } finally {
                    targetTensor?.close(); targetLengthTensor?.close(); hTensor?.close(); cTensor?.close()
                    // Intermediate tensors are now managed entirely by nested .use blocks
                }
            } // End while
            fTensor.close()
        } // End for
        Log.d(TAG, "RNN-T decoding finished.")
        return vocab.decode(hypothesis, language)
    }

    // --- (transcribeCtc remains the same) ---
    suspend fun transcribeCtc(filePath: String, language: String): String {
        if (!ensureCtcModelsLoaded()) return "[Error: CTC Models could not be loaded]"
        val encoderResult = runEncoder(filePath) ?: return "[Encoder Failed]"
        val (encoderOutputs, encoderShape, encodedLengths) = encoderResult
        val vocabList = vocab.getVocabFor(language) ?: return "[Error: No vocab for '$language']"
        val vocabSize = vocabList.size
        // Feed encoder output into CTC Decoder model
        val ctcDecoderInputTensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(encoderOutputs), encoderShape)
        Log.d(TAG, "Running CTC Decoder model...")
        val logits: FloatArray; val timeSteps: Int
        try {
            ctcDecoderSession!!.run(Collections.singletonMap("encoder_output", ctcDecoderInputTensor)).use { results ->
                (results[0] as OnnxTensor).use { logitsTensor -> // Use and close logitsTensor
                    val logitsShape = logitsTensor.info.shape
                    Log.d(TAG,"CTC Decoder output shape: ${logitsShape.contentToString()}")
                    if (logitsShape.size != 3 || logitsShape[0] != 1L || logitsShape[2] != vocabSize.toLong()) {
                        Log.e(TAG, "CTC Decoder output shape is unexpected! Expected [1, T, $vocabSize], Got ${logitsShape.contentToString()}")
                        return "[Error: CTC Decoder output shape mismatch]"
                    }
                    timeSteps = logitsShape[1].toInt()
                    logits = FloatArray(logitsTensor.floatBuffer.remaining())
                    logitsTensor.floatBuffer.get(logits)
                } // logitsTensor closed here
            } // results closed here
        } catch (e: Exception) { Log.e(TAG, "CTC Decoder run failed", e); return "[Error: CTC Decoder inference failed]" }
        finally { ctcDecoderInputTensor.close() }
        Log.d(TAG, "Running CTC Greedy Decode on logits (T=$timeSteps)...")
        val decodedIds = (0 until timeSteps).map { t ->
            val offset = t * vocabSize
            var maxIdx = 0; var maxVal = -Float.MAX_VALUE
            for (v in 0 until vocabSize) { val currentLogit = logits[offset + v]; if (currentLogit > maxVal) { maxVal = currentLogit; maxIdx = v } }
            maxIdx
        }
        val mergedIds = mutableListOf<Int>()
        var lastId = -1
        decodedIds.forEach { id -> if (id != lastId) { mergedIds.add(id); lastId = id } }
        return vocab.decode(mergedIds, language)
    }

    // --- (close remains the same) ---
    fun close() {
        Log.d(TAG, "Closing ASRService models if loaded.")
        synchronized(loadingLock) {
            encoderSession?.close()
            rnntDecoderSession?.close()
            jointEncSession?.close()
            jointPredSession?.close()
            jointPreNetSession?.close()
            ctcDecoderSession?.close()
            postNetSessions.values.forEach { it?.close() }
            postNetSessions.clear()
            areRnntModelsLoaded = false
            isCtcDecoderLoaded = false
            encoderSession = null; rnntDecoderSession = null; jointEncSession = null;
            jointPredSession = null; jointPreNetSession = null; ctcDecoderSession = null
        }
    }
}