package com.example.allrecorder.recordings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@Composable
fun AudioVisualizer(
    audioData: ByteArray,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.onPrimary
    val secondaryColor = MaterialTheme.colorScheme.tertiaryContainer

    // Create a gradient brush for the "thread"
    val brush = remember(primaryColor, secondaryColor) {
        Brush.horizontalGradient(listOf(primaryColor, secondaryColor))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight() // Fill the container height provided by the parent
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        val shortBuffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalSamples = shortBuffer.remaining()
        val samples = ShortArray(totalSamples)
        shortBuffer.get(samples)

        // We want a smooth wave, so we pick a small number of control points
        // Too many points make it jagged, too few make it flat.
        val pointsCount = 20
        val step = if (totalSamples > 0) totalSamples / pointsCount else 1

        val path = Path()
        path.moveTo(0f, centerY)

        // We will gather coordinates first to calculate control points for smooth curves
        val coordinates = mutableListOf<Pair<Float, Float>>()
        coordinates.add(0f to centerY) // Start point

        for (i in 1 until pointsCount) {
            val sampleIndex = i * step
            if (sampleIndex < totalSamples) {
                // Get average amplitude in this chunk to prevent extreme spikes from single samples
                var maxAmp = 0f
                val rangeEnd = (sampleIndex + step).coerceAtMost(totalSamples)
                for (j in sampleIndex until rangeEnd) {
                    val amp = abs(samples[j].toInt()).toFloat()
                    if (amp > maxAmp) maxAmp = amp
                }

                // Normalize amplitude (0.0 to 1.0)
                val normalizedAmp = (maxAmp / 32767f).coerceIn(0f, 1f)

                // Alternate up and down to create the "wave" effect
                // If i is even, go up; if odd, go down.
                val direction = if (i % 2 == 0) 1f else -1f
                val waveHeight = normalizedAmp * (height * 0.8f) / 2f // 80% of height max

                val x = i * (width / pointsCount)
                val y = centerY + (direction * waveHeight)
                coordinates.add(x to y)
            }
        }
        coordinates.add(width to centerY) // End point

        // Draw Cubic Bezier Curve through points
        if (coordinates.size > 1) {
            for (i in 0 until coordinates.size - 1) {
                val (p0x, p0y) = coordinates[i]
                val (p1x, p1y) = coordinates[i + 1]

                // Control points for smooth curvature
                val controlX1 = p0x + (p1x - p0x) / 2
                val controlY1 = p0y
                val controlX2 = p0x + (p1x - p0x) / 2
                val controlY2 = p1y

                path.cubicTo(controlX1, controlY1, controlX2, controlY2, p1x, p1y)
            }
        }

        drawPath(
            path = path,
            brush = brush,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}