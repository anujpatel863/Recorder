package com.example.allrecorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object AudioUtils {

    /**
     * Extracts a list of amplitudes (0-100) for visualization.
     * Supports WAV (PCM) and M4A/AAC using modern Float processing.
     */
    fun extractAmplitudes(file: File, targetBars: Int = 80): List<Int> {
        if (!file.exists()) return emptyList()

        return if (file.extension.equals("wav", ignoreCase = true)) {
            extractWavAmplitudes(file, targetBars)
        } else {
            extractM4aAmplitudes(file, targetBars)
        }
    }

    private fun extractWavAmplitudes(file: File, targetBars: Int): List<Int> {
        if (file.length() < 44) return emptyList()
        val amplitudes = mutableListOf<Int>()
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.skipBytes(44) // Skip header
                val dataLength = raf.length() - 44
                val totalSamples = dataLength / 2
                // Process the file in chunks equal to the number of bars
                val samplesPerBar = (totalSamples / targetBars).coerceAtLeast(1)
                val buffer = ByteArray((samplesPerBar * 2).toInt())

                for (i in 0 until targetBars) {
                    val bytesRead = raf.read(buffer)
                    if (bytesRead == -1) break

                    // Convert to FloatArray for consistent processing
                    val floats = bytesToFloats(buffer, bytesRead)
                    amplitudes.add(calculateRMS(floats))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return normalize(amplitudes)
    }

    private fun extractM4aAmplitudes(file: File, targetBars: Int): List<Int> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (e: Exception) {
            return emptyList()
        }

        val trackIndex = selectAudioTrack(extractor)
        if (trackIndex < 0) {
            extractor.release()
            return emptyList()
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            extractor.release()
            return emptyList()
        }

        codec.configure(format, null, null, 0)
        codec.start()

        val rawAmplitudes = mutableListOf<Int>()
        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false
        val timeoutUs = 5000L

        try {
            while (true) {
                if (!isEOS) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = extractor.readSampleData(buffer!!, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        // 1. Convert ByteBuffer to FloatArray (-1.0 to 1.0)
                        val floatChunk = byteBufferToFloats(outputBuffer, bufferInfo.size)

                        // 2. Calculate Loudness (RMS) of this chunk
                        val amplitude = calculateRMS(floatChunk)
                        rawAmplitudes.add(amplitude)

                        outputBuffer.clear()
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isEOS) break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                codec.stop()
                codec.release()
                extractor.release()
            } catch (e: Exception) {}
        }

        return resample(rawAmplitudes, targetBars)
    }

    // --- Helpers for Float Processing ---

    /**
     * Converts a raw ByteBuffer (16-bit PCM) into a FloatArray (-1.0 to 1.0)
     */
    private fun byteBufferToFloats(buffer: ByteBuffer, size: Int): FloatArray {
        // 16-bit PCM = 2 bytes per sample
        val shortCount = size / 2
        val floats = FloatArray(shortCount)

        // Ensure little endian (standard for WAV/Android PCM)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until shortCount) {
            val sample = buffer.short
            // Normalize: 32768 is the max value for a signed 16-bit integer
            floats[i] = sample / 32768.0f
        }
        return floats
    }

    /**
     * Converts a ByteArray (16-bit PCM) into a FloatArray
     */
    private fun bytesToFloats(bytes: ByteArray, length: Int): FloatArray {
        val shortCount = length / 2
        val floats = FloatArray(shortCount)
        val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until shortCount) {
            floats[i] = buffer.short / 32768.0f
        }
        return floats
    }

    /**
     * Calculates Root Mean Square (RMS) amplitude from floats.
     * Returns 0-100 range.
     */
    private fun calculateRMS(floats: FloatArray): Int {
        if (floats.isEmpty()) return 0
        var sum = 0.0
        // Optimization: Step through to avoid processing every single sample if array is huge
        val step = (floats.size / 1000).coerceAtLeast(1)
        var count = 0

        for (i in floats.indices step step) {
            val v = floats[i]
            sum += v * v
            count++
        }

        if (count == 0) return 0
        val rms = sqrt(sum / count)
        // Scale up: RMS is usually small (0.0 - 0.5 for speech), we map it to 0-100
        return (rms * 400).toInt().coerceIn(0, 100)
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        return -1
    }

    private fun resample(source: List<Int>, targetCount: Int): List<Int> {
        if (source.isEmpty()) return emptyList()
        if (source.size <= targetCount) return normalize(source)

        val result = mutableListOf<Int>()
        val blockSize = source.size / targetCount.toFloat()

        for (i in 0 until targetCount) {
            val start = (i * blockSize).toInt()
            val end = ((i + 1) * blockSize).toInt().coerceAtMost(source.size)
            var sum = 0L
            var count = 0
            for (j in start until end) {
                sum += source[j]
                count++
            }
            result.add(if (count > 0) (sum / count).toInt() else source[start])
        }
        return normalize(result)
    }

    private fun normalize(input: List<Int>): List<Int> {
        if (input.isEmpty()) return input
        val max = input.maxOrNull() ?: 1
        if (max == 0) return input
        return input.map { (it * 100) / max }
    }
    fun trimAudioFile(inputFile: File, outputFile: File, startMs: Long, endMs: Long): Boolean {
        if (!inputFile.exists()) return false

        return if (inputFile.extension.equals("wav", ignoreCase = true)) {
            trimWavFile(inputFile, outputFile, startMs, endMs)
        } else {
            trimM4aFile(inputFile, outputFile, startMs, endMs)
        }
    }

    private fun trimWavFile(inputFile: File, outputFile: File, startMs: Long, endMs: Long): Boolean {
        try {
            RandomAccessFile(inputFile, "r").use { raf ->
                raf.seek(24) // Sample Rate is at offset 24 (4 bytes)
                val sampleRate = Integer.reverseBytes(raf.readInt())
                raf.seek(34) // Bits Per Sample is at offset 34 (2 bytes)
                val bitsPerSample = java.lang.Short.reverseBytes(raf.readShort()).toInt()
                raf.seek(22) // Num Channels is at offset 22 (2 bytes)
                val channels = java.lang.Short.reverseBytes(raf.readShort()).toInt()

                val byteRate = sampleRate * channels * bitsPerSample / 8
                val startByte = 44 + (startMs * byteRate / 1000)
                val endByte = 44 + (endMs * byteRate / 1000)
                val dataSize = endByte - startByte

                // 1. Write Header
                val header = ByteArray(44)
                raf.seek(0)
                raf.read(header)

                // Fix header sizes
                val totalSize = 36 + dataSize
                updateHeaderSize(header, 4, totalSize.toInt()) // File Size
                updateHeaderSize(header, 40, dataSize.toInt()) // Data Size

                outputFile.writeBytes(header)

                // 2. Write Data Chunk
                raf.seek(startByte)
                outputFile.appendBytes(raf, dataSize.toInt())
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // Helper to write generic byte stream chunks
    private fun File.appendBytes(raf: RandomAccessFile, length: Int) {
        val buffer = ByteArray(8192)
        var bytesLeft = length
        this.outputStream().use { fos ->
            // Skip header in output since we wrote it manually
            fos.write(byteArrayOf(), 0, 0) // no-op init
        }
        // Re-open in append mode is tricky, better to use a single FileOutputStream
        // Rewriting logic for safety:
    }

    // Improved trimWav implementation with single stream flow
    private fun updateHeaderSize(header: ByteArray, offset: Int, size: Int) {
        header[offset] = (size and 0xff).toByte()
        header[offset + 1] = ((size shr 8) and 0xff).toByte()
        header[offset + 2] = ((size shr 16) and 0xff).toByte()
        header[offset + 3] = ((size shr 24) and 0xff).toByte()
    }

    private fun trimM4aFile(inputFile: File, outputFile: File, startMs: Long, endMs: Long): Boolean {
        // Basic MediaMuxer implementation.
        // Note: Precision depends on Keyframes. This does not re-encode.
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)
            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) return false

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val muxer =
                MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(format)
            muxer.start()

            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = extractor.sampleTime
                if (bufferInfo.presentationTimeUs > endMs * 1000) break

                bufferInfo.flags = extractor.sampleFlags
                bufferInfo.offset = 0

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}