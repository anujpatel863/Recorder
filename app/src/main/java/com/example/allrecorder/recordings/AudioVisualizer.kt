package com.example.allrecorder.recordings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * LIVE Recording Visualizer
 * Used by MainActivity.
 * Visualizes raw byte data from the microphone.
 */
@Composable
fun AudioVisualizer(
    audioData: ByteArray,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary

    // Gradient Brush
    val brush = remember(primaryColor, secondaryColor) {
        Brush.verticalGradient(listOf(secondaryColor, primaryColor, secondaryColor))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        if (audioData.isEmpty()) return@Canvas

        val width = size.width
        val height = size.height
        val centerY = height / 2f

        // Convert raw bytes to shorts
        val shortBuffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalSamples = shortBuffer.remaining()

        // Fixed bar count for live view
        val barCount = 40
        val step = if (totalSamples > 0) totalSamples / barCount else 1

        val barWidth = width / barCount
        val gap = barWidth * 0.3f
        val actualBarWidth = (barWidth - gap).coerceAtLeast(2f)

        for (i in 0 until barCount) {
            val sampleIndex = i * step
            if (sampleIndex < totalSamples) {
                // Find max amplitude in this small window
                var maxAmp = 0f
                val rangeEnd = (sampleIndex + step).coerceAtMost(totalSamples)
                // Skip samples for performance if window is large
                val skip = (rangeEnd - sampleIndex) / 20 + 1

                for (j in sampleIndex until rangeEnd step skip) {
                    val sample = shortBuffer.get(j).toInt()
                    val amp = abs(sample).toFloat()
                    if (amp > maxAmp) maxAmp = amp
                }

                // Normalize (16-bit max is 32768)
                // Multiply by 2.5f to make it look responsive even for quiet speech
                val normalizedAmp = (maxAmp / 32768f * 2.5f).coerceIn(0.02f, 1f)

                val barHeight = normalizedAmp * (height * 0.8f)
                val xOffset = i * barWidth + (gap / 2)

                // Draw Mirrored Bar (Center outwards)
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(xOffset, centerY - (barHeight / 2)),
                    size = Size(actualBarWidth, barHeight),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
    }
}

/**
 * PLAYBACK Waveform / Scrubber
 * Used by RecordingsScreen.
 * Visualizes extracted amplitude list (from M4A or WAV).
 */
@Composable
fun PlaybackWaveform(
    amplitudes: List<Int>,
    progress: Float, // 0.0 to 1.0
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val playedColor = MaterialTheme.colorScheme.primary
    // [MODIFIED] Changed to explicit Gray as requested
    val unplayedColor = Color.Gray.copy(alpha = 0.6f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        if (amplitudes.isEmpty()) return@Canvas

        val barCount = amplitudes.size
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        val barWidth = width / barCount
        val gap = barWidth * 0.3f
        val actualBarWidth = (barWidth - gap).coerceAtLeast(1f)

        amplitudes.forEachIndexed { index, amp ->
            // Normalize: Inputs are 0-100
            val normalizedAmp = (amp / 100f).coerceIn(0.05f, 1f)
            val barHeight = normalizedAmp * (height * 0.9f)
            val xOffset = index * barWidth

            val isPlayed = (index.toFloat() / barCount) <= progress

            drawRoundRect(
                color = if (isPlayed) playedColor else unplayedColor,
                topLeft = Offset(xOffset, centerY - (barHeight / 2)),
                size = Size(actualBarWidth, barHeight),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}