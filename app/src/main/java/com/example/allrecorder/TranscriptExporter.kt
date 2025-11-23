package com.example.allrecorder

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.regex.Pattern

object TranscriptExporter {

    enum class Format { TXT, SRT }

    // Regex to parse your specific format: "[12.5s - 14.2s] Speaker 1: Hello"
    private val SEGMENT_PATTERN = Pattern.compile("\\[(\\d+\\.\\d+)s - (\\d+\\.\\d+)s\\]\\s*(?:Speaker (\\d+):\\s*)?(.*)")

    fun export(context: Context, recording: Recording, format: Format) {
        val text = recording.transcript ?: return
        // Use a safe filename
        val safeName = File(recording.filePath).nameWithoutExtension.replace("[^a-zA-Z0-9.-]".toRegex(), "_")

        val (content, extension, mime) = when (format) {
            Format.TXT -> Triple(text, "txt", "text/plain")
            Format.SRT -> Triple(convertToSrt(text), "srt", "application/x-subrip")
        }

        saveToDownloads(context, "$safeName.$extension", content, mime)
    }

    private fun convertToSrt(rawText: String): String {
        val sb = StringBuilder()
        var counter = 1

        rawText.lines().forEach { line ->
            val matcher = SEGMENT_PATTERN.matcher(line)
            if (matcher.find()) {
                val startSec = matcher.group(1)?.toFloatOrNull() ?: 0f
                val endSec = matcher.group(2)?.toFloatOrNull() ?: 0f
                val speakerId = matcher.group(3) // Can be null
                val content = matcher.group(4) ?: ""

                sb.append(counter++).append("\n")
                sb.append(formatSrtTime(startSec)).append(" --> ").append(formatSrtTime(endSec)).append("\n")

                if (speakerId != null) {
                    sb.append("Speaker ").append(speakerId).append(": ")
                }
                sb.append(content.trim()).append("\n\n")
            }
        }
        return sb.toString()
    }

    private fun formatSrtTime(seconds: Float): String {
        val hrs = (seconds / 3600).toInt()
        val mins = ((seconds % 3600) / 60).toInt()
        val secs = (seconds % 60).toInt()
        val millis = ((seconds * 1000) % 1000).toInt()
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hrs, mins, secs, millis)
    }

    private fun saveToDownloads(context: Context, filename: String, content: String, mimeType: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AllRecorder")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        out.write(content.toByteArray())
                    }
                    showToast(context, "Exported to Downloads: $filename")
                }
            } else {
                // Legacy storage (ensure WRITE_EXTERNAL_STORAGE is granted if targeting old Android)
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { it.write(content.toByteArray()) }
                showToast(context, "Exported: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(context, "Export Failed: ${e.message}")
        }
    }

    // [FIX] Helper to show Toast on Main Thread from Background Thread
    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}