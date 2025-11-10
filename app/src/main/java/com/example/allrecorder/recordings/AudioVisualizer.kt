package com.example.allrecorder.recordings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@Composable
fun AudioVisualizer(audioData: ByteArray) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        val width = size.width
        val height = size.height
        val middle = height / 2f

        val shortBuffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(shortBuffer.remaining())
        shortBuffer.get(samples)

        val maxAmplitude = 32767f
        val barWidth = 2f
        val barSpacing = 1f
        val totalBarWidth = barWidth + barSpacing
        val numBars = (width / totalBarWidth).toInt()
        val samplesPerBar = samples.size / numBars

        for (i in 0 until numBars) {
            val startSample = i * samplesPerBar
            val endSample = startSample + samplesPerBar
            var maxSample = 0
            for (j in startSample until endSample) {
                if (j < samples.size) {
                    if (abs(samples[j].toInt()) > maxSample) {
                        maxSample = abs(samples[j].toInt())
                    }
                }
            }

            val barHeight = (maxSample / maxAmplitude) * height
            val x = i * totalBarWidth
            drawLine(
                color = Color.Gray,
                start = Offset(x, middle - barHeight / 2),
                end = Offset(x, middle + barHeight / 2),
                strokeWidth = barWidth
            )
        }
    }
}
