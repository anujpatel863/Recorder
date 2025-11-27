package com.example.allrecorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * A modern approach to decoding compressed audio (M4A, AAC, MP3) into
 * raw PCM Float arrays required by AI models (Sherpa-ONNX).
 */
object AudioDecoder {
    private const val TAG = "AudioDecoder"
    private const val TIMEOUT_US = 5000L

    /**
     * Decodes an audio file into a FloatArray of normalized PCM samples (-1.0 to 1.0).
     *
     * @param audioFilePath Path to the input file (m4a, mp3, wav, etc.)
     * @param //expectedSampleRate The sample rate the AI expects (default 16000).
     * Note: This decoder currently assumes the input file matches
     * this rate or that the AI can handle the file's native rate.
     * For robust production apps, a Resampler is needed if rates mismatch.
     */
    fun decodeToPcm(audioFilePath: String): FloatArray? {
        val file = File(audioFilePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $audioFilePath")
            return null
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioFilePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set data source", e)
            return null
        }

        val trackIndex = selectAudioTrack(extractor)
        if (trackIndex < 0) {
            Log.e(TAG, "No audio track found in file")
            extractor.release()
            return null
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""

        // Check sample rate (Optional: You could throw error or warn if mismatch)
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else -1

        Log.d(TAG, "Decoding $mime at ${sampleRate}Hz")

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create decoder for $mime", e)
            extractor.release()
            return null
        }

        codec.configure(format, null, null, 0)
        codec.start()

        val decodedSamples = mutableListOf<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false

        try {
            while (true) {
                if (!isEOS) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                // End of file
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEOS = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null) {
                        // Convert ByteBuffer to FloatArray
                        // PCM 16-bit is 2 bytes per sample
                        val pcmData = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmData)
                        outputBuffer.clear()

                        // Convert bytes to normalized floats
                        val floats = convertPcm16ToFloat(pcmData)
                        decodedSamples.addAll(floats.toList())
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isEOS) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during decoding loop", e)
            return null
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        return decodedSamples.toFloatArray()
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }

    private fun convertPcm16ToFloat(pcmData: ByteArray): FloatArray {
        val shorts = ShortArray(pcmData.size / 2)
        ByteBuffer.wrap(pcmData).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val floats = FloatArray(shorts.size)
        for (i in shorts.indices) {
            // Normalize 16-bit integer (-32768 to 32767) to Float (-1.0 to 1.0)
            floats[i] = shorts[i] / 32768.0f
        }
        return floats
    }
}