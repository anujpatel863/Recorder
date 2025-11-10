package com.example.allrecorder

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FinalTranscriptSegment(
    val speakerId: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

class TranscriptionOrchestrator(private val context: Context) {

    private val vadService = VADService(context)
    private val diarizationService = DiarizationService(context)
    private val asrService = AsrService(context)

    companion object {
        private const val TAG = "TranscriptionOrchestrator"
    }

    suspend fun transcribe(filePath: String, language: String): List<FinalTranscriptSegment> {
        Log.i(TAG, "Starting full transcription process for: $filePath")

        // 1. Read Audio File
        val audioData = readAudioFile(filePath)
        if (audioData.isEmpty()) {
            Log.e(TAG, "Audio data is empty. Aborting.")
            return emptyList()
        }

        // 2. Voice Activity Detection
        Log.d(TAG, "Step 1: Performing Voice Activity Detection.")
        val speechSegments = vadService.getSpeechSegments(audioData)
        if (speechSegments.isEmpty()) {
            Log.w(TAG, "No speech segments detected. Aborting.")
            return emptyList()
        }

        // 3. Speaker Diarization
        Log.d(TAG, "Step 2: Performing Speaker Diarization on ${speechSegments.size} segments.")
        diarizationService.reset() // Ensure state is clean for each run
        val diarizationResult = diarizationService.process(speechSegments, audioData)
        if (diarizationResult.isEmpty()) {
            Log.w(TAG, "Diarization did not return any results. Aborting.")
            return emptyList()
        }

        // 4. Transcription
        Log.d(TAG, "Step 3: Transcribing ${diarizationResult.size} diarized chunks.")
        val finalTranscript = mutableListOf<FinalTranscriptSegment>()
        for (result in diarizationResult) {
            // Use the more efficient method that accepts a FloatArray directly.
            val transcriptText = asrService.transcribeCtc(result.audioChunk, language)

            if (transcriptText.isNotBlank() && !transcriptText.startsWith("[Error")) {
                finalTranscript.add(
                    FinalTranscriptSegment(
                        speakerId = result.speakerId,
                        startTimeMs = result.segment.start,
                        endTimeMs = result.segment.end,
                        text = transcriptText.trim()
                    )
                )
                Log.d(TAG, "Speaker ${result.speakerId} [${result.segment.start}-${result.segment.end}ms]: $transcriptText")
            } else {
                Log.w(TAG, "Skipping empty or error transcription for speaker ${result.speakerId}")
            }
        }

        Log.i(TAG, "Transcription process finished. Generated ${finalTranscript.size} segments.")
        return mergeConsecutiveSegments(finalTranscript)
    }

    private fun readAudioFile(filePath: String): FloatArray {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "Audio file not found: $filePath")
            return FloatArray(0)
        }

        try {
            FileInputStream(file).use { fileStream ->
                // Skip WAV header
                fileStream.skip(44)
                val dataBytes = fileStream.readBytes()
                val buffer = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                val numSamples = dataBytes.size / 2
                val floatArray = FloatArray(numSamples)
                for (i in 0 until numSamples) {
                    floatArray[i] = buffer.short.toFloat() / 32768.0f
                }
                return floatArray
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read WAV file for orchestration", e)
            return FloatArray(0)
        }
    }

    private fun mergeConsecutiveSegments(segments: List<FinalTranscriptSegment>): List<FinalTranscriptSegment> {
        if (segments.isEmpty()) return emptyList()

        val merged = mutableListOf<FinalTranscriptSegment>()
        var current = segments.first()

        for (i in 1 until segments.size) {
            val next = segments[i]
            if (next.speakerId == current.speakerId) {
                // Merge text and update end time
                current = FinalTranscriptSegment(
                    speakerId = current.speakerId,
                    startTimeMs = current.startTimeMs,
                    endTimeMs = next.endTimeMs,
                    text = "${current.text} ${next.text}"
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current) // Add the last segment

        return merged
    }


    fun close() {
        vadService.close()
        diarizationService.close()
        asrService.close()
        Log.i(TAG, "TranscriptionOrchestrator and all its services closed.")
    }
}
