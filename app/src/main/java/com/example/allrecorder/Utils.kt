package com.example.allrecorder

import android.content.Context
import java.util.concurrent.TimeUnit
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
fun formatDuration(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
fun printTfLiteModelDetails(context: Context, modelPath: String) {
    val TAG = "TfLiteDetails"
    try {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

        val interpreter = Interpreter(modelBuffer)

        val inputTensorCount = interpreter.inputTensorCount
        Log.d(TAG, "--- Model: $modelPath ---")
        Log.d(TAG, "Input Tensor Count: $inputTensorCount")
        for (i in 0 until inputTensorCount) {
            val tensor = interpreter.getInputTensor(i)
            val shape = tensor.shape().joinToString()
            val dtype = tensor.dataType()
            Log.d(TAG, "Input Tensor $i: Shape=[$shape], DataType=$dtype")
        }

        val outputTensorCount = interpreter.outputTensorCount
        Log.d(TAG, "Output Tensor Count: $outputTensorCount")
        for (i in 0 until outputTensorCount) {
            val tensor = interpreter.getOutputTensor(i)
            val shape = tensor.shape().joinToString()
            val dtype = tensor.dataType()
            Log.d(TAG, "Output Tensor $i: Shape=[$shape], DataType=$dtype")
        }

        interpreter.close()
        Log.d(TAG, "--- End of Details ---")

    } catch (e: Exception) {
        Log.e(TAG, "Failed to get details for model: $modelPath", e)
    }
}