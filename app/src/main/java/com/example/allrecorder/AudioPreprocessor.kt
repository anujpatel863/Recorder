package com.example.allrecorder



import android.content.Context
import org.jtransforms.fft.FloatFFT_1D // Make sure this import is correct for jTransforms
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.* // For cos, log, max

class AudioPreprocessor(context: Context) {

    // --- Parameters ---
    private val sampleRate: Int = 16000
    private val nFft: Int = 512
    private val nWindowSize: Int = 400
    private val nHopLength: Int = 160
    val nMels: Int = 80
    private val fMin: Float = 0.0f
    private val fMax: Float = 8000.0f
    private val power: Float = 2.0f
    private val ditherVal: Float = 1e-5f
    private val epsilon: Float = 1e-10f // For log calculation stability
    // --- End Parameters ---

    private val fft = FloatFFT_1D(nFft.toLong())
    private val window = createHannWindow(nWindowSize)
    private val melBasisMatrix: Array<FloatArray>

    init {
        val melBasisPath = "indic_model/mel_basis_80_512.txt"
        melBasisMatrix = loadMelBasis(context, melBasisPath)
        if (melBasisMatrix.isEmpty() || melBasisMatrix[0].isEmpty()) {
            throw RuntimeException("Failed to load Mel Basis Matrix")
        }
        // Optional: Verify shape
        // println("Loaded Mel Basis: ${melBasisMatrix.size} x ${melBasisMatrix[0].size}") // Should be 80 x 257
    }

    // --- Core Processing Function ---
    fun process(audioFloats: FloatArray): FloatArray {
        // 1. Framing & Windowing
        val frames = frameAndWindowAudio(audioFloats)

        if (frames.isEmpty()) {
            return FloatArray(0) // Return empty if no frames
        }

        val numFrames = frames.size
        // Intermediate storage for log-mel frames (Frames x Mels)
        val logMelSpectrogram = Array(numFrames) { FloatArray(nMels) }

        // Temporary buffer for FFT (reused)
        // jTransforms expects [re0, im0, re1, im1, ...] for realForward
        val fftBuffer = FloatArray(nFft)

        for (i in frames.indices) {
            val windowedFrame = frames[i]

            // Reset and Pad buffer
            fftBuffer.fill(0.0f)
            System.arraycopy(windowedFrame, 0, fftBuffer, 0, windowedFrame.size)

            // 2. FFT (in-place)
            fft.realForward(fftBuffer)

            // 3. Power Spectrum (Calculate magnitude squared)
            // Output size is nFft / 2 + 1 = 257
            val powerSpec = calculatePowerSpectrum(fftBuffer)

            // 4. (Optional) Dithering - Apply to power spectrum
            applyDithering(powerSpec, ditherVal)

            // 5. Apply Mel Filterbank (Matrix Multiplication)
            val melFrame = applyMelFilterbank(powerSpec, melBasisMatrix) // Size = nMels (80)

            // 6. Log Compression
            applyLogCompression(melFrame, epsilon) // In-place log

            // Store the resulting log-mel frame
            logMelSpectrogram[i] = melFrame
        }

        // 7. Format for ONNX: Need (Batch=1, Mels=80, Frames)
        // Transpose (Frames x Mels) -> (Mels x Frames) and flatten
        val transposedFlattened = FloatArray(nMels * numFrames)
        for (m in 0 until nMels) {
            for (f in 0 until numFrames) {
                transposedFlattened[m * numFrames + f] = logMelSpectrogram[f][m]
            }
        }

        return transposedFlattened // Final flattened array for OnnxTensor
    }

    // --- Helper Functions ---

    private fun createHannWindow(size: Int): FloatArray {
        val window = FloatArray(size)
        for (i in 0 until size) {
            window[i] = (0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))).toFloat()
        }
        return window
    }

    private fun frameAndWindowAudio(audio: FloatArray, applyWindow: Boolean = true): List<FloatArray> {
        val frames = mutableListOf<FloatArray>()
        var start = 0
        while (start + nWindowSize <= audio.size) {
            val frame = audio.copyOfRange(start, start + nWindowSize)
            if (applyWindow) {
                for (i in frame.indices) {
                    frame[i] *= window[i]
                }
            }
            frames.add(frame)
            start += nHopLength
        }
        return frames
    }

    // Corrected Power Spectrum calculation for jTransforms output format
    private fun calculatePowerSpectrum(fftResult: FloatArray): FloatArray {
        val numBins = nFft / 2 + 1
        val power = FloatArray(numBins)

        // DC component (real only)
        power[0] = fftResult[0] * fftResult[0]

        // Intermediate bins (real and imaginary pairs)
        // Loop goes up to numBins - 1 because the last bin (Nyquist) is special
        for (k in 1 until numBins - 1) {
            val real = fftResult[2 * k]
            val imag = fftResult[2 * k + 1]
            power[k] = real * real + imag * imag
        }

        // Nyquist component (real only, exists only if nFft is even)
        // In jTransforms' realForward format for even N, the Nyquist is at index 1
        if (nFft % 2 == 0) {
            power[numBins - 1] = fftResult[1] * fftResult[1]
        }
        // Note: If nFft is odd, jTransforms might handle the last element differently,
        // but nFft=512 is even, so this should be correct.

        return power
    }

    private fun applyDithering(data: FloatArray, ditherValue: Float) {
        if (ditherValue > 0) {
            for (i in data.indices) {
                // Simple uniform random noise, scale appropriately if needed
                data[i] += (Math.random().toFloat() * 2f - 1f) * ditherValue
            }
        }
    }

    private fun applyMelFilterbank(powerSpecFrame: FloatArray, melBasis: Array<FloatArray>): FloatArray {
        if (powerSpecFrame.size != melBasis[0].size) {
            throw IllegalArgumentException("Power spectrum size (${powerSpecFrame.size}) != Mel basis input size (${melBasis[0].size})")
        }
        val melOutput = FloatArray(nMels)
        for (i in 0 until nMels) {
            var sum = 0.0f
            for (j in powerSpecFrame.indices) {
                sum += melBasis[i][j] * powerSpecFrame[j] // Dot product
            }
            melOutput[i] = sum
        }
        return melOutput
    }

    // Apply log compression in-place
    private fun applyLogCompression(melFrame: FloatArray, epsilon: Float) {
        for (i in melFrame.indices) {
            melFrame[i] = ln(max(melFrame[i], epsilon).toDouble()).toFloat()
        }
    }

    private fun loadMelBasis(context: Context, filename: String): Array<FloatArray> {
        val melList = mutableListOf<FloatArray>()
        try {
            context.assets.open(filename).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val values = line!!.trim().split("\\s+".toRegex()) // Split by whitespace
                            .mapNotNull { it.toFloatOrNull() } // Convert to Float
                        if (values.isNotEmpty()) {
                            melList.add(values.toFloatArray())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace() // Log error
            // Return empty or handle error appropriately
            return emptyArray()
        }
        // Optional: Verify shape after loading
        // if (melList.isNotEmpty() && (melList.size != nMels || melList[0].size != (nFft / 2 + 1))) {
        //      Log.e("AudioPreprocessor", "Mel basis loaded with unexpected shape!")
        // }
        return melList.toTypedArray()
    }
}