package com.example.allrecorder.recordings

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.linc.audiowaveform.AudioWaveform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

// ... [Keep AudioVisualizer function exactly as it is] ...
@Composable
fun AudioVisualizer(
    audioData: ByteArray,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    useGradient: Boolean = true
) {
    // ... [Your existing code for AudioVisualizer] ...
    val pointsCount = 40
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
    // [FIX] Using the external library handles the drag gesture automatically.
    // It takes the progress (0f..1f) and calls onProgressChange with the new progress (0f..1f).
    AudioWaveform(
        modifier = modifier,
        amplitudes = amplitudes,
        progress = progress,
        onProgressChange = { newPercent ->
            onSeek(newPercent)
        },
        waveformBrush = SolidColor(barColor.copy(alpha = 0.5f)),
        progressBrush = SolidColor(playedColor),
        spikeWidth = 4.dp,
        spikePadding = 2.dp,
        spikeRadius = 4.dp
    )
}