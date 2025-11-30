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

@Composable
fun PlaybackWaveform(
    amplitudes: List<Int>,
    progress: Float, // 0f to 1f
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    playedColor: Color = MaterialTheme.colorScheme.tertiary
) {
    val density = LocalDensity.current

    // Internal state to track if the user is currently scrubbing
    var dragProgress by remember { mutableStateOf<Float?>(null) }

    // [LOGIC] If dragging, show the drag position. If playing, show the player position.
    val displayProgress = dragProgress ?: progress

    val currentOnSeek by rememberUpdatedState(onSeek)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val precisionThresholdPx = with(density) { 100.dp.toPx() } // Increased for better feel

                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat()

                    if (width > 1f) {
                        // 1. Calculate start position
                        val startX = down.position.x
                        val startY = down.position.y

                        // 2. Initial seek to touch point
                        val initialProgress = (startX / width).coerceIn(0f, 1f)
                        dragProgress = initialProgress
                        currentOnSeek(initialProgress)
                        down.consume()

                        var currentDragX = startX
                        var accumulatedProgress = initialProgress

                        var change = down
                        while (true) {
                            val event = awaitPointerEvent()
                            val nextChange = event.changes.firstOrNull { it.id == change.id }

                            if (nextChange == null || !nextChange.pressed) {
                                // Drag finished
                                dragProgress = null
                                break
                            }

                            val currentX = nextChange.position.x
                            val currentY = nextChange.position.y

                            // [FIX 1] Precision Math that never goes negative
                            val verticalDist = abs(currentY - startY)

                            // If user drags down, sensitivity drops from 100% to 10%
                            val sensitivity = if (verticalDist > precisionThresholdPx) {
                                val distanceOverThreshold = verticalDist - precisionThresholdPx
                                // Decay formula: Smoothly reduce sensitivity
                                val decay = (1f - (distanceOverThreshold / (precisionThresholdPx * 3)))
                                decay.coerceIn(0.1f, 1f) // Clamp: Never go below 10% speed
                            } else {
                                1f
                            }

                            // [FIX 2] Use Delta accumulation
                            val dx = currentX - change.position.x
                            val changeAmount = (dx / width) * sensitivity

                            accumulatedProgress = (accumulatedProgress + changeAmount).coerceIn(0f, 1f)

                            // Update internal state (UI updates immediately)
                            dragProgress = accumulatedProgress

                            // Notify external listener (Player seeks)
                            // Optimization: In a real app, you might want to debounce this call
                            currentOnSeek(accumulatedProgress)

                            nextChange.consume()
                            change = nextChange
                        }
                    }
                }
            }
    ) {
        val totalBars = amplitudes.size
        if (totalBars == 0) return@Canvas

        val effectiveBarWidth = size.width / totalBars
        val spacing = effectiveBarWidth * 0.25f // 25% spacing
        val drawBarWidth = effectiveBarWidth - spacing

        amplitudes.forEachIndexed { index, amplitude ->
            // Normalize amplitude to 0.0 - 1.0 range (assuming 100 is max in your list)
            val percent = (amplitude / 100f).coerceIn(0.1f, 1f)
            val barHeight = size.height * percent

            val x = index * effectiveBarWidth
            val y = (size.height - barHeight) / 2

            // Use displayProgress (which respects dragging)
            val isPlayed = (index.toFloat() / totalBars) <= displayProgress
            val color = if (isPlayed) playedColor else barColor.copy(alpha = 0.5f)

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(drawBarWidth, barHeight),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}