package com.example.allrecorder

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

object MelSpectrogram {
    private const val SAMPLE_RATE = 16000
    private const val N_FFT = 400
    private const val HOP_LENGTH = 160
    private const val N_MELS = 80

    // Lazy initialization of complex objects
    private val melFilterbank: Array<FloatArray> by lazy { createMelFilterbank() }
    private val hannWindow: FloatArray by lazy { createHannWindow() }

    fun create(audioData: FloatArray): Array<FloatArray> {
        // 1. Apply Short-Time Fourier Transform (STFT)
        val stftMatrix = shortTimeFourierTransform(audioData)

        // 2. Convert complex numbers to magnitudes (absolute values)
        val magnitudes = stftMatrix.map { frame ->
            FloatArray(frame.size / 2) { i ->
                val real = frame[2 * i]
                val imag = frame[2 * i + 1]
                sqrt(real * real + imag * imag)
            }
        }.toTypedArray()

        // 3. Apply the Mel filterbank to the magnitudes
        val melSpectrogram = applyMelFilterbank(magnitudes)

        // 4. Convert to a logarithmic scale (decibels)
        return melSpectrogram.map { frame ->
            FloatArray(frame.size) { i ->
                20 * log10(frame[i].coerceAtLeast(1e-5f))
            }
        }.toTypedArray()
    }

    private fun shortTimeFourierTransform(audioData: FloatArray): Array<FloatArray> {
        val fft = FloatFFT_1D(N_FFT.toLong())
        val frameCount = (audioData.size - N_FFT) / HOP_LENGTH + 1
        val stftResult = Array(frameCount) { FloatArray(N_FFT * 2) }

        for (i in 0 until frameCount) {
            val start = i * HOP_LENGTH
            val frame = FloatArray(N_FFT) { j ->
                if (start + j < audioData.size) {
                    audioData[start + j] * hannWindow[j]
                } else {
                    0f
                }
            }
            val fftInput = FloatArray(N_FFT * 2)
            for (j in frame.indices) {
                fftInput[2 * j] = frame[j]
            }
            fft.complexForward(fftInput)
            stftResult[i] = fftInput
        }
        return stftResult
    }

    private fun createHannWindow(): FloatArray {
        return FloatArray(N_FFT) { i ->
            (0.5 * (1 - cos(2 * Math.PI * i / (N_FFT - 1)))).toFloat()
        }
    }

    private fun createMelFilterbank(): Array<FloatArray> {
        val minMel = 0.0
        val maxMel = 2595.0 * log10(1.0 + (SAMPLE_RATE / 2.0) / 700.0)
        val melPoints = DoubleArray(N_MELS + 2) { i ->
            minMel + i * (maxMel - minMel) / (N_MELS + 1)
        }
        val hzPoints = melPoints.map { mel -> 700.0 * (10.0.pow(mel / 2595.0) - 1.0) }
        val binPoints = hzPoints.map { hz -> floor((N_FFT + 1) * hz / SAMPLE_RATE).toInt() }

        val filters = Array(N_MELS) { FloatArray(N_FFT / 2 + 1) }
        for (i in 0 until N_MELS) {
            val start = binPoints[i]
            val center = binPoints[i + 1]
            val end = binPoints[i + 2]
            for (j in start until center) {
                filters[i][j] = (j - start).toFloat() / (center - start).toFloat()
            }
            for (j in center until end) {
                filters[i][j] = (end - j).toFloat() / (end - center).toFloat()
            }
        }
        return filters
    }

    private fun applyMelFilterbank(magnitudes: Array<FloatArray>): Array<FloatArray> {
        val melSpectrogram = Array(magnitudes.size) { FloatArray(N_MELS) }
        for (i in magnitudes.indices) {
            for (j in 0 until N_MELS) {
                var sum = 0f
                for (k in 0 until (N_FFT / 2 + 1)) {
                    sum += magnitudes[i][k] * melFilterbank[j][k]
                }
                melSpectrogram[i][j] = sum
            }
        }
        return melSpectrogram
    }
}