package com.example.allrecorder

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

object AudioUtils {
    fun extractAmplitudes(file: File, targetBars: Int = 80): List<Int> {
        if (!file.exists() || file.length() < 44) return emptyList()
        val amplitudes = mutableListOf<Int>()
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.skipBytes(44)
                val dataLength = raf.length() - 44
                val totalSamples = dataLength / 2
                val samplesPerBar = (totalSamples / targetBars).coerceAtLeast(1)
                val buffer = ByteArray((samplesPerBar * 2).toInt())
                for (i in 0 until targetBars) {
                    val bytesRead = raf.read(buffer)
                    if (bytesRead == -1) break
                    var sum = 0L
                    var count = 0
                    for (j in 0 until bytesRead step 2) {
                        if (j + 1 < bytesRead) {
                            val sample = (buffer[j].toInt() and 0xFF) or (buffer[j+1].toInt() shl 8)
                            sum += abs(sample.toShort().toInt())
                            count++
                        }
                    }
                    amplitudes.add(if (count > 0) (sum / count).toInt() else 0)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        val max = amplitudes.maxOrNull() ?: 1
        return amplitudes.map { (it * 100) / max }
    }
}