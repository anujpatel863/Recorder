package com.example.allrecorder.recordings

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/**
 * LIVE Recording Visualizer - "Liquid Thread" Style
 * Clean Design: No Shadow, Horizontal Gradient, Smooth Interpolation.
 */
@Composable
fun AudioVisualizer(
    audioData: ByteArray,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    useGradient: Boolean = true,
    modifier: Modifier = Modifier
) {
    // [ROBUSTNESS] Temporal Smoothing State
    val pointsCount = 40
    var smoothedAmplitudes by remember { mutableStateOf(FloatArray(pointsCount)) }

    // Infinite transition for the "idle" breathing effect
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        // 1. Setup Gradient Brush
        // Changed to HORIZONTAL gradient for better visibility along the wave
        val brush = if (useGradient) {
            Brush.horizontalGradient(
                colors = listOf(activeColor, secondaryColor),
                startX = 0f,
                endX = width
            )
        } else {
            SolidColor(activeColor)
        }

        // 2. Parse Audio Data
        val currentAmplitudes = FloatArray(pointsCount)
        if (audioData.isNotEmpty()) {
            val shortBuffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val totalSamples = shortBuffer.remaining()
            val step = totalSamples / pointsCount.toFloat()

            for (i in 0 until pointsCount) {
                val index = (i * step).toInt().coerceIn(0, totalSamples - 1)
                // Window average for stability
                var sum = 0f
                val windowSize = 3
                var count = 0
                for(w in -windowSize..windowSize) {
                    val sampleIdx = (index + w).coerceIn(0, totalSamples - 1)
                    sum += abs(shortBuffer.get(sampleIdx).toInt())
                    count++
                }
                currentAmplitudes[i] = (sum / count) / 32768f // Normalize 0..1
            }
        }

        // 3. Temporal Smoothing (Decay Logic)
        for (i in 0 until pointsCount) {
            val target = currentAmplitudes[i]
            val current = smoothedAmplitudes[i]

            if (target > current) {
                smoothedAmplitudes[i] = lerp(current, target, 0.6f) // Fast attack
            } else {
                smoothedAmplitudes[i] = lerp(current, target, 0.15f) // Slow decay
            }
        }

        // 4. Build Cubic Path
        val path = Path()

        val pointXStep = width / (pointsCount - 1)
        val pathPoints = mutableListOf<Offset>()

        for (i in 0 until pointsCount) {
            val x = i * pointXStep

            // Apply Idle Wave + Audio Amplitude
            val idleWave = sin(i * 0.2f + phase) * (height * 0.02f)
            val audioHeight = smoothedAmplitudes[i] * (height * 0.5f) // Increased height sensitivity slightly

            // Center Bias: Make center of screen react more than edges
            val centerBias = sin(Math.PI * (i.toFloat() / pointsCount)).toFloat()
            val finalY = centerY - (audioHeight * centerBias) + idleWave

            pathPoints.add(Offset(x, finalY))
        }

        if (pathPoints.isNotEmpty()) {
            path.moveTo(pathPoints[0].x, pathPoints[0].y)
            for (i in 0 until pathPoints.size - 1) {
                val p0 = pathPoints[i]
                val p1 = pathPoints[i+1]

                // Control points for cubic bezier (smooth transition)
                val cp1 = Offset((p0.x + p1.x) / 2, p0.y)
                val cp2 = Offset((p0.x + p1.x) / 2, p1.y)

                path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p1.x, p1.y)
            }
        }

        // 5. Draw Main Thread (Clean, No Shadow)
        drawPath(
            path = path,
            brush = brush,
            style = Stroke(
                width = 4.dp.toPx(), // Main thick line
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

// Helper for interpolation
private fun lerp(start: Float, stop: Float, amount: Float): Float {
    return start + (stop - start) * amount
}

/**
 * PLAYBACK Waveform / Scrubber
 * (Unchanged)
 */
@Composable
fun PlaybackWaveform(
    amplitudes: List<Int>,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val unplayedColor = Color.Gray.copy(alpha = 0.6f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
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