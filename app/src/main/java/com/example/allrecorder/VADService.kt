package com.example.allrecorder

import android.content.Context
import android.util.Log
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import java.lang.Exception

data class SpeechSegment(val start: Long, val end: Long)

class VADService(private val context: Context) {

    private var vad: VadSilero? = null

    // Define frame size and sample rate as constants for calculations
    private val sampleRate = SampleRate.SAMPLE_RATE_16K
    private val frameSize = FrameSize.FRAME_SIZE_512
    private val sampleRateHz = 16000

    companion object {
        private const val TAG = "VADService"
    }

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            // This builder is copied from the README's Java example
            // It does NOT use a listener
            vad = Vad.builder()
                .setContext(context)
                .setSampleRate(sampleRate)
                .setFrameSize(frameSize)
                .setMode(Mode.NORMAL)
                // Using your Orchestrator's original values
                .setSilenceDurationMs(100)
                .setSpeechDurationMs(250)
                .build()

            Log.i(TAG, "Silero VAD initialized successfully (simple loop mode).")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Silero VAD", e)
        }
    }

    /**
     * Processes an entire FloatArray of audio data by streaming it chunk by chunk
     * and manually creating timestamps.
     *
     * This logic is adapted from the README's WebRTC file processing example.
     */
    fun getSpeechSegments(audioData: FloatArray): List<SpeechSegment> {
        val currentVad = vad ?: run {
            Log.e(TAG, "VAD is not initialized.")
            return emptyList()
        }

        if (audioData.isEmpty()) {
            Log.w(TAG, "Audio data is empty, cannot perform VAD.")
            return emptyList()
        }

        Log.d(TAG, "Starting VAD on audio with ${audioData.size} samples (simple loop).")

        val segments = mutableListOf<SpeechSegment>()
        val shortAudioData = floatToShortArray(audioData)
        val frameSizeInSamples = frameSize.value

        var isSpeaking = false
        var speechStartMs: Long = 0
        var currentSample: Int = 0

        // Loop through the audio data in chunks (frames)
        while (currentSample < shortAudioData.size) {
            val remaining = shortAudioData.size - currentSample
            val currentFrameSize = if (remaining >= frameSizeInSamples) {
                frameSizeInSamples
            } else {
                remaining
            }

            // Get the chunk
            val chunk = shortAudioData.copyOfRange(currentSample, currentSample + currentFrameSize)

            // Pad the chunk if it's smaller than the required frame size (last chunk)
            val frame = if (chunk.size < frameSizeInSamples) {
                chunk.plus(ShortArray(frameSizeInSamples - chunk.size))
            } else {
                chunk
            }

            // This is the "simple" way from the README
            val isSpeech = try {
                currentVad.isSpeech(frame)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing VAD frame", e)
                false // Assume not speech on error
            }

            // Calculate current time in ms
            // This calculation is based on the start of the *current chunk*
            val currentTimeMs = (currentSample.toLong() * 1000) / sampleRateHz

            // --- State logic based on the README's WebRTC example ---
            if (isSpeech && !isSpeaking) {
                // Start of a speech segment
                isSpeaking = true
                speechStartMs = currentTimeMs
            } else if (!isSpeech && isSpeaking) {
                // End of a speech segment
                isSpeaking = false
                segments.add(SpeechSegment(speechStartMs, currentTimeMs))
            }
            // --- End of state logic ---

            currentSample += currentFrameSize
        }

        // Handle case where audio ends while still speaking
        if (isSpeaking) {
            val endTimeMs = (shortAudioData.size.toLong() * 1000) / sampleRateHz
            segments.add(SpeechSegment(speechStartMs, endTimeMs))
        }

        Log.i(TAG, "VAD processing complete. Found ${segments.size} speech segments.")
        return segments
    }

    /**
     * Converts a FloatArray of normalized audio data [-1.0, 1.0] to a ShortArray of 16-bit PCM data.
     */
    private fun floatToShortArray(floatArray: FloatArray): ShortArray {
        val shortArray = ShortArray(floatArray.size)
        for (i in floatArray.indices) {
            // Clamp the value to be safe, then scale and convert
            val clampedValue = floatArray[i].coerceIn(-1.0f, 1.0f)
            shortArray[i] = (clampedValue * 32767.0f).toInt().toShort()
        }
        return shortArray
    }

    fun close() {
        vad?.close()
        vad = null
        Log.d(TAG, "VADService closed.")
    }
}