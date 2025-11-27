package com.example.allrecorder.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.example.allrecorder.AudioUtils
import com.example.allrecorder.EmbeddingManager
import com.example.allrecorder.Recording
import com.example.allrecorder.RecordingDao
import com.example.allrecorder.SettingsManager
import com.example.allrecorder.TranscriptionOrchestrator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingsRepository @Inject constructor(
    private val recordingDao: RecordingDao,
    private val transcriptionOrchestrator: TranscriptionOrchestrator,
    private val embeddingManager: EmbeddingManager,
    @param:ApplicationContext private val context: Context
) {

    fun getAllRecordings(): Flow<List<Recording>> = recordingDao.getAllRecordings()

    fun getStarredRecordings(): Flow<List<Recording>> = recordingDao.getStarredRecordings()

    // [NEW] Generic Update for Tags (and other fields)
    suspend fun updateRecording(recording: Recording) = withContext(Dispatchers.IO) {
        recordingDao.update(recording)
    }

    suspend fun loadAmplitudes(recording: Recording): List<Int> = withContext(Dispatchers.IO) {
        try {
            val file = File(recording.filePath)
            if (file.exists()) {
                AudioUtils.extractAmplitudes(file)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("RecordingsRepository", "Error loading amplitudes", e)
            emptyList()
        }
    }

    suspend fun renameRecording(recording: Recording, newName: String) = withContext(Dispatchers.IO) {
        val oldFile = File(recording.filePath)
        val newFile = File(oldFile.parent, "$newName.wav") // Assuming .wav for now - ideally preserve extension
        if (oldFile.renameTo(newFile)) {
            val renamedRecording = recording.copy(filePath = newFile.absolutePath)
            recordingDao.update(renamedRecording)
        }
    }

    suspend fun deleteRecording(recording: Recording) = withContext(Dispatchers.IO) {
        val file = File(recording.filePath)
        if (file.exists()) {
            file.delete()
        }
        recordingDao.delete(recording)
    }

    suspend fun toggleStar(recording: Recording) = withContext(Dispatchers.IO) {
        val updatedRecording = recording.copy(isStarred = !recording.isStarred)
        recordingDao.update(updatedRecording)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun saveRecordingAs(recording: Recording) = withContext(Dispatchers.IO) {
        try {
            val srcFile = File(recording.filePath)
            if (!srcFile.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: File not found.", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }
            // Preserve original extension
            val extension = srcFile.extension
            val fileName = "Saved_${srcFile.nameWithoutExtension}.$extension"
            val mimeType = if (extension.equals("m4a", true)) "audio/mp4" else "audio/wav"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AllRecorder")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri).use { outputStream ->
                    FileInputStream(srcFile).use { inputStream ->
                        inputStream.copyTo(outputStream!!)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Saved to Downloads/AllRecorder", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save file.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun transcribeRecording(
        recording: Recording,
        onProgress: (Float, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (recording.processingStatus == Recording.STATUS_PROCESSING) return@withContext

        recordingDao.update(recording.copy(processingStatus = Recording.STATUS_PROCESSING))

        try {
            var inputPath = recording.filePath

            if (SettingsManager.asrEnhancementEnabled) {
                onProgress(0f, "Enhancing audio...")
                val enhancedPath = transcriptionOrchestrator.enhanceAudio(recording.filePath)
                if (enhancedPath != null) {
                    inputPath = enhancedPath
                }
            }

            onProgress(0.1f, "Transcribing...")
            val segments = transcriptionOrchestrator.transcribe(
                filePath = inputPath,
                language = SettingsManager.asrLanguage,
                modelName = SettingsManager.asrModel,
                onProgress = { p ->
                    onProgress(0.1f + (p * 0.8f), "Transcribing ${(p * 100).toInt()}%")
                }
            )

            val sb = StringBuilder()
            segments.forEach { seg ->
                val timeStr = String.format(Locale.US, "%.1fs - %.1fs", seg.start, seg.end)
                val speakerLabel = if (seg.speakerId > 0) "Speaker ${seg.speakerId}: " else ""
                sb.append("[$timeStr] $speakerLabel${seg.text}\n")
            }
            val fullTranscript = sb.toString().trim()

            onProgress(0.9f, "Indexing...")

            // [UPDATED] Respect Semantic Search Setting
            val vector = if (SettingsManager.semanticSearchEnabled) {
                embeddingManager.generateEmbedding(fullTranscript)
            } else {
                null
            }

            val updated = recording.copy(
                transcript = fullTranscript,
                embedding = vector,
                processingStatus = Recording.STATUS_COMPLETED
            )
            recordingDao.update(updated)
            onProgress(1.0f, "Done")

        } catch (e: Exception) {
            e.printStackTrace()
            val failed = recording.copy(processingStatus = Recording.STATUS_FAILED)
            recordingDao.update(failed)
            onProgress(0f, "Failed")
        }
    }

    suspend fun performSemanticSearch(query: String): List<Recording> {
        // [UPDATED] Hybrid Search Logic

        // 1. If Semantic Search is DISABLED, fallback to simple text search.
        if (!SettingsManager.semanticSearchEnabled) {
            val allRecs = recordingDao.getAllRecordingsForSearch()
            return allRecs.filter { rec ->
                rec.transcript?.contains(query, ignoreCase = true) == true ||
                        File(rec.filePath).name.contains(query, ignoreCase = true)
            }
        }

        // 2. If Semantic Search is ENABLED, perform vector search.
        val queryVector = embeddingManager.generateEmbedding(query) ?: return emptyList()
        val allRecs = recordingDao.getAllRecordingsForSearch()

        return allRecs.mapNotNull { rec ->
            val recVector = rec.embedding
            if (recVector != null) {
                val score = embeddingManager.cosineSimilarity(queryVector, recVector)
                if (score > 0.3f) rec to score else null
            } else null
        }.sortedByDescending { it.second }
            .map { it.first }
    }

    // [NEW] Backfill Logic
    suspend fun getMissingEmbeddingsCount(): Int {
        return recordingDao.getRecordingsMissingEmbeddings().size
    }

    suspend fun reindexAllMissing(onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        val missing = recordingDao.getRecordingsMissingEmbeddings()
        val total = missing.size

        missing.forEachIndexed { index, rec ->
            // Double check transcript exists
            val text = rec.transcript
            if (!text.isNullOrBlank()) {
                val vector = embeddingManager.generateEmbedding(text)
                if (vector != null) {
                    recordingDao.update(rec.copy(embedding = vector))
                }
            }
            onProgress(index + 1, total)
        }
    }

    suspend fun updateTranscript(recording: Recording, newText: String) = withContext(Dispatchers.IO) {
        val updated = recording.copy(transcript = newText)
        recordingDao.update(updated)
    }

    suspend fun cleanUpOldRecordings(daysToKeep: Int, protectedTag: String): Int = withContext(Dispatchers.IO) {
        // Calculate the cutoff time (Current Time - Days in Millis)
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)

        // 1. Get candidates from DB (Old & Not Starred)
        val candidates = recordingDao.getOldNonStarredRecordings(cutoffTime)

        var deletedCount = 0

        candidates.forEach { recording ->
            // 2. Secondary check: Do NOT delete if it has the protected tag
            val hasProtectedTag = recording.tags.contains(protectedTag)

            if (!hasProtectedTag) {
                // Safe to delete
                deleteRecording(recording)
                deletedCount++
            }
        }

        return@withContext deletedCount
    }
}