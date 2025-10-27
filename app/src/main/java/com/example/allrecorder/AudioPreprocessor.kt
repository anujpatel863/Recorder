package com.example.allrecorder

import android.content.Context
import android.util.Log
import org.jtransforms.fft.FloatFFT_1D
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.*
import java.util.Random

class AudioPreprocessor(context: Context) {

    // --- Parameters ---
    private val sampleRate: Int = 16000
    private val nFft: Int = 512
    private val nWindowSize: Int = 400
    private val nHopLength: Int = 160
    val nMels: Int = 80
    private val power: Float = 2.0f
    private val ditherVal: Float = 1e-5f
    private val epsilon: Float = 1e-10f
    private val centerPadding: Boolean = true
    // --- End Parameters ---

    private val fft = FloatFFT_1D(nFft.toLong())
    private val window = createHannWindow(nWindowSize)
    private val melBasisMatrix: Array<FloatArray>
    private val random = Random()

    companion object {
        private const val TAG = "AudioPreprocessor"
    }

    init {
        val melBasisPath = "indic_model/mel_basis_80_512.txt"
        melBasisMatrix = loadMelBasis(context, melBasisPath)
        if (melBasisMatrix.isEmpty() || melBasisMatrix.size != nMels || melBasisMatrix[0].size != (nFft / 2 + 1)) {
            Log.e(TAG, "Failed to load Mel Basis Matrix or shape is incorrect. Expected ${nMels}x${nFft / 2 + 1}, Got ${melBasisMatrix.size}x${melBasisMatrix.getOrNull(0)?.size}")
            throw RuntimeException("Failed to load valid Mel Basis Matrix from $melBasisPath")
        } else {
            Log.d(TAG, "Mel Basis Matrix loaded successfully (${melBasisMatrix.size}x${melBasisMatrix[0].size})")
        }
    }

    fun process(audioFloats: FloatArray): FloatArray {
        if (audioFloats.isEmpty()) {
            Log.w(TAG, "Input audio array is empty.")
            return FloatArray(0)
        }

        // --- Step 1: Dithering ---
        val ditheredAudio = applyDithering(audioFloats, ditherVal)

        // --- Step 2: Centered Padding (Zero Padding) ---
        val paddedAudio = if (centerPadding) {
            val padding = nFft / 2
            val result = FloatArray(ditheredAudio.size + padding * 2) { 0.0f }
            System.arraycopy(ditheredAudio, 0, result, padding, ditheredAudio.size)
            Log.d(TAG, "Applied ZERO center padding. Original size: ${audioFloats.size}, Padded size: ${result.size}")
            result
        } else {
            ditheredAudio
        }

        // --- Step 3: Framing & Windowing ---
        val frames = frameAndWindowAudio(paddedAudio)
        if (frames.isEmpty()) {
            Log.w(TAG, "Framing resulted in zero frames.")
            return FloatArray(0)
        }
        val numFrames = frames.size
        Log.d(TAG, "Framing created $numFrames frames.")

        val logMelSpectrogram = Array(numFrames) { FloatArray(nMels) }
        val fftBuffer = FloatArray(nFft)

        // --- Steps 4, 5, 6, 7: Process Each Frame ---
        for (i in frames.indices) {
            val windowedFrame = frames[i]

            fftBuffer.fill(0.0f)
            System.arraycopy(windowedFrame, 0, fftBuffer, 0, windowedFrame.size)
            fft.realForward(fftBuffer) // 4. FFT

            val powerSpec = calculatePowerSpectrum(fftBuffer) // 5. Power Spectrum
            val melFrame = applyMelFilterbank(powerSpec, melBasisMatrix) // 6. Mel Filterbank
            applyLogCompression(melFrame, epsilon) // 7. Log Compression

            logMelSpectrogram[i] = melFrame
        }

        // --- FIX: Step 8: Per-Feature / Utterance-Level Normalization ---
        // This is a standard step in NeMo that is easily missed.
        // It subtracts the mean of the entire spectrogram from every value.
        normalizeLogMels(logMelSpectrogram)
        Log.d(TAG, "Applied utterance-level mean normalization.")
        // --- End of Fix ---

        // --- Step 9: Transpose and Flatten for ONNX ---
        val transposedFlattened = FloatArray(nMels * numFrames)
        for (m in 0 until nMels) {
            for (f in 0 until numFrames) {
                transposedFlattened[m * numFrames + f] = logMelSpectrogram[f][m]
            }
        }
        Log.d(TAG, "Preprocessing complete. Output shape (flattened): ${transposedFlattened.size} (Mels=$nMels, Frames=$numFrames)")
        return transposedFlattened
    }

    // --- FIX: New helper function for normalization ---
    private fun normalizeLogMels(logMels: Array<FloatArray>) {
        if (logMels.isEmpty()) return

        // 1. Calculate the global mean
        var sum = 0.0
        var count = 0
        for (frame in logMels) {
            for (value in frame) {
                sum += value
                count++
            }
        }
        val mean = (sum / count).toFloat()

        // 2. Subtract the mean from every element
        for (i in logMels.indices) {
            for (j in logMels[i].indices) {
                logMels[i][j] -= mean
            }
        }

        // Optional: Add std-dev normalization if this doesn't work
        // (but mean normalization is the most common and critical step)
    }
    // --- End of Fix ---

    private fun applyDithering(data: FloatArray, ditherValue: Float): FloatArray {
        if (ditherValue == 0f) return data
        val ditheredData = FloatArray(data.size)
        for (i in data.indices) {
            ditheredData[i] = data[i] + (random.nextFloat() * 2f - 1f) * ditherValue
        }
        Log.d(TAG, "Applied dithering with value: $ditherValue")
        return ditheredData
    }

    private fun createHannWindow(size: Int): FloatArray {
        val window = FloatArray(size)
        val denom = size // Use size for torch compatibility
        for (i in 0 until size) {
            window[i] = (0.5 * (1.0 - cos(2.0 * PI * i / denom))).toFloat()
        }
        return window
    }

    private fun frameAndWindowAudio(audio: FloatArray): List<FloatArray> {
        val frames = mutableListOf<FloatArray>()
        var start = 0
        while (start + nWindowSize <= audio.size) {
            val frame = FloatArray(nWindowSize)
            for (j in 0 until nWindowSize) {
                frame[j] = audio[start + j] * window[j]
            }
            frames.add(frame)
            start += nHopLength
        }
        return frames
    }

    private fun calculatePowerSpectrum(fftResult: FloatArray): FloatArray {
        val numBins = nFft / 2 + 1
        val powerSpec = FloatArray(numBins)
        powerSpec[0] = fftResult[0].pow(power)
        for (k in 1 until numBins - 1) {
            val real = fftResult[2 * k]
            val imag = fftResult[2 * k + 1]
            powerSpec[k] = (real * real + imag * imag)
        }
        if (nFft % 2 == 0) {
            powerSpec[numBins - 1] = fftResult[1].pow(power)
        }
        return powerSpec
    }

    private fun applyMelFilterbank(powerSpecFrame: FloatArray, melBasis: Array<FloatArray>): FloatArray {
        if (powerSpecFrame.size != melBasis[0].size) {
            Log.e(TAG, "Power spectrum size (${powerSpecFrame.size}) != Mel basis input size (${melBasis[0].size})")
            return FloatArray(nMels)
        }
        val melOutput = FloatArray(nMels)
        for (i in 0 until nMels) {
            var melEnergy = 0.0f
            for (j in powerSpecFrame.indices) {
                melEnergy += melBasis[i][j] * powerSpecFrame[j]
            }
            melOutput[i] = melEnergy
        }
        return melOutput
    }

    private fun applyLogCompression(melFrame: FloatArray, epsilon: Float) {
        for (i in melFrame.indices) {
            melFrame[i] = ln(max(melFrame[i], epsilon))
        }
    }

    private fun loadMelBasis(context: Context, filename: String): Array<FloatArray> {
        val melList = mutableListOf<FloatArray>()
        try {
            context.assets.open(filename).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        val values = line.trim().split("\\s+".toRegex())
                            .mapNotNull { it.toFloatOrNull() }
                        if (values.isNotEmpty()) {
                            melList.add(values.toFloatArray())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Mel Basis from $filename", e)
            return emptyArray()
        }
        return melList.toTypedArray()
    }
}