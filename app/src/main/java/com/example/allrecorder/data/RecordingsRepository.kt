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

    // [NEW] Smart "Duplicate" with optional Tag
    suspend fun duplicateRecording(recording: Recording, tagToAdd: String? = null) = withContext(Dispatchers.IO) {
        val originalFile = File(recording.filePath)
        if (!originalFile.exists()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Original file not found.", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }

        // Smart Naming: Handle duplicates like "Name (Copy)", "Name (Copy 2)"
        val parent = originalFile.parentFile
        val name = originalFile.nameWithoutExtension
        val ext = originalFile.extension

        var copyName = "$name (Copy)"
        var copyFile = File(parent, "$copyName.$ext")
        var counter = 1

        while (copyFile.exists()) {
            counter++
            copyName = "$name (Copy $counter)"
            copyFile = File(parent, "$copyName.$ext")
        }

        try {
            originalFile.copyTo(copyFile)

            // Calculate new tags
            val newTags = if (tagToAdd != null && !recording.tags.contains(tagToAdd)) {
                recording.tags + tagToAdd
            } else {
                recording.tags
            }

            // Create new entry
            val newRecording = recording.copy(
                id = 0, // Auto-generate
                filePath = copyFile.absolutePath,
                startTime = System.currentTimeMillis(), // Bring to top
                tags = newTags,
                isStarred = false // Don't auto-star duplicates
            )
            recordingDao.insert(newRecording)
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to duplicate file.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // [NEW] Smart "Save As New" (Copy with specific details)
    suspend fun saveAsNewRecording(
        originalRecording: Recording,
        newName: String,
        newTags: List<String>,
        newTranscript: String
    ) = withContext(Dispatchers.IO) {
        val originalFile = File(originalRecording.filePath)
        if (!originalFile.exists()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Original file not found.", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }

        // 1. Determine unique file name
        val parent = originalFile.parentFile
        val extension = originalFile.extension
        var safeName = newName
        var destFile = File(parent, "$safeName.$extension")
        var counter = 1

        while (destFile.exists()) {
            safeName = "$newName ($counter)"
            destFile = File(parent, "$safeName.$extension")
            counter++
        }

        try {
            // 2. Physical Copy
            originalFile.copyTo(destFile)

            // 3. Generate Embedding if transcript changed
            val finalEmbedding = if (newTranscript != originalRecording.transcript) {
                if (SettingsManager.semanticSearchEnabled && newTranscript.isNotBlank()) {
                    embeddingManager.generateEmbedding(newTranscript)
                } else null
            } else {
                originalRecording.embedding
            }

            // 4. Insert into DB
            val newRecording = originalRecording.copy(
                id = 0,
                filePath = destFile.absolutePath,
                startTime = System.currentTimeMillis(),
                tags = newTags,
                transcript = newTranscript.ifBlank { null },
                embedding = finalEmbedding,
                isStarred = false,
                processingStatus = if (newTranscript.isNotBlank()) Recording.STATUS_COMPLETED else originalRecording.processingStatus
            )
            recordingDao.insert(newRecording)

        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to create copy.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // [NEW] Smart "Update" (Rename + Tag + Transcript)
    suspend fun updateRecordingDetails(
        recording: Recording,
        newName: String,
        newTags: List<String>,
        newTranscript: String
    ) = withContext(Dispatchers.IO) {
        var currentRecording = recording
        val originalFile = File(recording.filePath)

        // 1. Handle Rename if needed
        if (originalFile.exists() && originalFile.nameWithoutExtension != newName) {
            val parent = originalFile.parentFile
            val extension = originalFile.extension
            val newFile = File(parent, "$newName.$extension")

            if (!newFile.exists() && originalFile.renameTo(newFile)) {
                currentRecording = currentRecording.copy(filePath = newFile.absolutePath)
            }
        }

        // 2. Handle Transcript & Embedding
        var updatedEmbedding = currentRecording.embedding
        if (newTranscript != currentRecording.transcript) {
            updatedEmbedding = if (SettingsManager.semanticSearchEnabled && newTranscript.isNotBlank()) {
                embeddingManager.generateEmbedding(newTranscript)
            } else null
        }

        // 3. Update DB
        val updated = currentRecording.copy(
            tags = newTags,
            transcript = newTranscript.ifBlank { null },
            embedding = updatedEmbedding,
            processingStatus = if (newTranscript.isNotBlank()) Recording.STATUS_COMPLETED else currentRecording.processingStatus
        )

        recordingDao.update(updated)
    }

    // [NEW] Trim Recording Logic
    suspend fun trimRecording(
        originalRecording: Recording,
        startMs: Long,
        endMs: Long,
        saveAsNew: Boolean,
        tagToAdd: String? = null
    ) = withContext(Dispatchers.IO) {
        val originalFile = File(originalRecording.filePath)
        if (!originalFile.exists()) return@withContext

        // Prepare Output File
        val parent = originalFile.parentFile
        val extension = originalFile.extension
        val name = originalFile.nameWithoutExtension

        val destFile = if (saveAsNew) {
            var copyName = "$name-Trimmed"
            var f = File(parent, "$copyName.$extension")
            var c = 1
            while (f.exists()) {
                copyName = "$name-Trimmed $c"
                f = File(parent, "$copyName.$extension")
                c++
            }
            f
        } else {
            // For overwrite, we write to a temp file first
            File(parent, "temp_trim.$extension")
        }

        // Perform Trim
        val success = AudioUtils.trimAudioFile(originalFile, destFile, startMs, endMs)

        if (success) {
            val newDuration = endMs - startMs

            // Calculate tags
            val updatedTags = if (tagToAdd != null && !originalRecording.tags.contains(tagToAdd)) {
                originalRecording.tags + tagToAdd
            } else {
                originalRecording.tags
            }

            if (saveAsNew) {
                // Insert New
                val newRec = originalRecording.copy(
                    id = 0,
                    filePath = destFile.absolutePath,
                    startTime = System.currentTimeMillis(),
                    duration = newDuration,
                    transcript = null, // Transcript invalid after trim
                    embedding = null,
                    processingStatus = Recording.STATUS_NOT_STARTED,
                    isStarred = false,
                    tags = updatedTags
                )
                recordingDao.insert(newRec)
            } else {
                // Overwrite Logic
                if (destFile.renameTo(originalFile)) {
                    // Update Existing
                    val updated = originalRecording.copy(
                        duration = newDuration,
                        transcript = null,
                        embedding = null,
                        processingStatus = Recording.STATUS_NOT_STARTED,
                        tags = updatedTags
                    )
                    recordingDao.update(updated)
                } else {
                    // Fallback swap if rename fails
                    destFile.copyTo(originalFile, overwrite = true)
                    destFile.delete()
                    val updated = originalRecording.copy(
                        duration = newDuration,
                        transcript = null,
                        embedding = null,
                        processingStatus = Recording.STATUS_NOT_STARTED,
                        tags = updatedTags
                    )
                    recordingDao.update(updated)
                }
            }
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Trim failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Standard Methods ---

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
            val extension = srcFile.extension
            val fileName = "Saved_${srcFile.nameWithoutExtension}.$extension"
            val mimeType = if (extension.equals("m4a", true)) "audio/mp4" else "audio/wav"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AllRecorder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri).use { outputStream ->
                    FileInputStream(srcFile).use { inputStream ->
                        inputStream.copyTo(outputStream!!)
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
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
        if (!SettingsManager.semanticSearchEnabled) {
            val allRecs = recordingDao.getAllRecordingsForSearch()
            return allRecs.filter { rec ->
                rec.transcript?.contains(query, ignoreCase = true) == true ||
                        File(rec.filePath).name.contains(query, ignoreCase = true)
            }
        }

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

    suspend fun getMissingEmbeddingsCount(): Int {
        return recordingDao.getRecordingsMissingEmbeddings().size
    }

    suspend fun reindexAllMissing(onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        val missing = recordingDao.getRecordingsMissingEmbeddings()
        val total = missing.size

        missing.forEachIndexed { index, rec ->
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
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        val candidates = recordingDao.getOldNonStarredRecordings(cutoffTime)

        var deletedCount = 0

        candidates.forEach { recording ->
            val hasProtectedTag = recording.tags.contains(protectedTag)
            if (!hasProtectedTag) {
                deleteRecording(recording)
                deletedCount++
            }
        }
        return@withContext deletedCount
    }
}