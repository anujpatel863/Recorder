package com.example.allrecorder.recordings

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun AudioVisualizer(
    audioData: ByteArray,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    useGradient: Boolean = true
) {
    val pointsCount = 40
    // [OPTIMIZATION] 1. Pre-calculate raw amplitudes only when data changes
    val rawAmplitudes = remember(audioData) {
        val amplitudes = FloatArray(pointsCount)
        if (audioData.isNotEmpty()) {
            val shortBuffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val totalSamples = shortBuffer.remaining()
            val step = totalSamples / pointsCount.toFloat()

            for (i in 0 until pointsCount) {
                val index = (i * step).toInt().coerceIn(0, totalSamples - 1)
                var sum = 0f
                val windowSize = 3
                var count = 0
                for(w in -windowSize..windowSize) {
                    val sampleIdx = (index + w).coerceIn(0, totalSamples - 1)
                    sum += abs(shortBuffer.get(sampleIdx).toInt())
                    count++
                }
                amplitudes[i] = (sum / count) / 32768f // Normalize 0..1
            }
        }
        amplitudes
    }

    // State for temporal smoothing (visual only)
    var smoothedAmplitudes by remember { mutableStateOf(FloatArray(pointsCount)) }

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

    Canvas(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        val brush = if (useGradient) {
            Brush.horizontalGradient(colors = listOf(activeColor, secondaryColor), startX = 0f, endX = width)
        } else {
            SolidColor(activeColor)
        }

        // [OPTIMIZATION] 2. Only handle smoothing and drawing in the Draw phase
        for (i in 0 until pointsCount) {
            val target = rawAmplitudes[i]
            val current = smoothedAmplitudes[i]
            smoothedAmplitudes[i] = if (target > current) lerp(current, target, 0.6f) else lerp(current, target, 0.15f)
        }

        val path = Path()
        val pointXStep = width / (pointsCount - 1)
        val pathPoints = mutableListOf<Offset>()

        for (i in 0 until pointsCount) {
            val x = i * pointXStep
            val idleWave = sin(i * 0.2f + phase) * (height * 0.02f)
            val audioHeight = smoothedAmplitudes[i] * (height * 0.5f)
            val centerBias = sin(Math.PI * (i.toFloat() / pointsCount)).toFloat()
            val finalY = centerY - (audioHeight * centerBias) + idleWave
            pathPoints.add(Offset(x, finalY))
        }

        if (pathPoints.isNotEmpty()) {
            path.moveTo(pathPoints[0].x, pathPoints[0].y)
            for (i in 0 until pathPoints.size - 1) {
                val p0 = pathPoints[i]
                val p1 = pathPoints[i+1]
                val cp1 = Offset((p0.x + p1.x) / 2, p0.y)
                val cp2 = Offset((p0.x + p1.x) / 2, p1.y)
                path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p1.x, p1.y)
            }
        }

        drawPath(path = path, brush = brush, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun lerp(start: Float, stop: Float, amount: Float): Float = start + (stop - start) * amount

/**
 * PLAYBACK Waveform / Scrubber
 * [IMPROVED] Supports Tap, Drag, AND Precision Scrubbing (Vertical Drag)
 */
@Composable
fun PlaybackWaveform(
    amplitudes: List<Int>,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    val density = LocalDensity.current
    // Threshold to engage precision mode (drag down 50dp)
    val precisionThresholdPx = with(density) { 50.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat()
                    val initialY = down.position.y

                    if (width > 0f) {
                        // Initial seek
                        onSeek((down.position.x / width).coerceIn(0f, 1f))
                        down.consume()
                    }

                    var change = down
                    while (true) {
                        val event = awaitPointerEvent()
                        val nextChange = event.changes.firstOrNull { it.id == change.id } ?: break
                        if (!nextChange.pressed) break

                        val currentX = nextChange.position.x
                        val currentY = nextChange.position.y

                        // [PRECISION SEEKING]
                        // If user drags finger vertically away from bar, slow down seeking
                        val verticalDist = abs(currentY - initialY)
                        val sensitivity = if (verticalDist > precisionThresholdPx) {
                            // Scale sensitivity based on distance.
                            // At 50dp = 1.0x, at 200dp ~= 0.2x
                            val factor = 1f - ((verticalDist - precisionThresholdPx) / (precisionThresholdPx * 4)).coerceIn(0f, 0.9f)
                            factor
                        } else {
                            1f
                        }

                        if (width > 0f) {
                            // We calculate delta from previous X to apply sensitivity
                            val dx = currentX - change.position.x
                            val currentProgress = progress // Note: This captures the progress passed in composition
                            val newProgress = (currentProgress + (dx / width) * sensitivity).coerceIn(0f, 1f)

                            // Only trigger seek if there was movement
                            if (abs(dx) > 0.5f) {
                                onSeek(newProgress)
                            }
                        }

                        nextChange.consume()
                        change = nextChange
                    }
                }
            }
    ) {
        val totalBars = amplitudes.size
        if (totalBars == 0) return@Canvas

        val effectiveBarWidth = size.width / totalBars
        val spacing = effectiveBarWidth * 0.2f
        val drawBarWidth = effectiveBarWidth - spacing

        amplitudes.forEachIndexed { index, amplitude ->
            val percent = amplitude / 100f
            val barHeight = size.height * percent
            val x = index * effectiveBarWidth
            val y = (size.height - barHeight) / 2

            val isPlayed = (index.toFloat() / totalBars) <= progress
            val color = if (isPlayed) barColor else barColor.copy(alpha = 0.5f)

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(drawBarWidth, barHeight),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}