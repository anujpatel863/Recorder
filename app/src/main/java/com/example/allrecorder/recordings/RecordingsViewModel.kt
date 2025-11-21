package com.example.allrecorder.recordings

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaPlayer
import android.os.IBinder
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.allrecorder.*
import com.example.allrecorder.models.ModelManager
import com.example.allrecorder.models.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    private val recordingDao: RecordingDao = AppDatabase.getDatabase(application).recordingDao()
    private val modelManager = ModelManager(application)
    private val embeddingManager = EmbeddingManager(application)

    private var transcriptionOrchestrator: TranscriptionOrchestrator? = null

    var isServiceRecording by mutableStateOf(RecordingService.isRecording)
        private set

    private var mediaPlayer: MediaPlayer? = null
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    private var progressUpdateJob: Job? = null

    private val _audioData = MutableStateFlow(ByteArray(0))
    val audioData: StateFlow<ByteArray> = _audioData.asStateFlow()

    private var recordingService: RecordingService? = null
    private var isBound by mutableStateOf(false)

    private val _searchResults = MutableStateFlow<List<RecordingUiState>?>(null)
    val searchResults: StateFlow<List<RecordingUiState>?> = _searchResults.asStateFlow()

    private data class TranscriptionProgress(
        val recordingId: Long? = null,
        val progress: Float = 0f,
        val message: String = ""
    )

    data class RecordingUiState(
        val recording: Recording,
        val liveProgress: Float? = null,
        val liveMessage: String? = null
    )

    private val _transcriptionProgress = MutableStateFlow(TranscriptionProgress())
    private val _allRecordingsInternal = recordingDao.getAllRecordings().asLiveData()
    private val _transcriptionProgressLiveData = _transcriptionProgress.asLiveData()

    val allRecordings: MediatorLiveData<List<RecordingUiState>> = MediatorLiveData()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            isBound = true
            viewModelScope.launch {
                recordingService?.audioDataFlow?.collectLatest {
                    _audioData.value = it
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            recordingService = null
        }
    }

    init {
        allRecordings.addSource(_allRecordingsInternal) { recordings ->
            val progress = _transcriptionProgressLiveData.value ?: TranscriptionProgress()
            allRecordings.value = combineRecordingAndProgress(recordings, progress)
        }
        allRecordings.addSource(_transcriptionProgressLiveData) { progress ->
            val recordings = _allRecordingsInternal.value ?: emptyList()
            allRecordings.value = combineRecordingAndProgress(recordings, progress)
        }
        viewModelScope.launch {
            while(true) {
                if (isServiceRecording != RecordingService.isRecording) {
                    isServiceRecording = RecordingService.isRecording
                }
                delay(500)
            }
        }
    }

    private fun getOrchestrator(): TranscriptionOrchestrator {
        transcriptionOrchestrator?.let { return it }
        val newOrchestrator = TranscriptionOrchestrator(getApplication())
        transcriptionOrchestrator = newOrchestrator
        return newOrchestrator
    }

    fun transcribeRecording(context: Context, recording: Recording) {
        if (recording.processingStatus == Recording.STATUS_PROCESSING) {
            Toast.makeText(context, "Already processing...", Toast.LENGTH_SHORT).show()
            return
        }

        val language = SettingsManager.asrLanguage.ifEmpty { "en" }
        val modelName = SettingsManager.asrModel
        val enhancementEnabled = SettingsManager.asrEnhancementEnabled

        // --- 1. CRITICAL CHECK: ASR Bundle (Required) ---
        val asrBundleId = "bundle_asr_$modelName"
        val asrBundle = ModelRegistry.getBundle(asrBundleId)

        if (asrBundle == null || !modelManager.isBundleReady(asrBundle)) {
            Toast.makeText(context, "Critical: ASR Model '$modelName' is missing. Please download it in Settings.", Toast.LENGTH_LONG).show()
            return
        }

        // --- 2. OPTIONAL CHECK: Diarization ---
        val diarizationBundle = ModelRegistry.getBundle("bundle_diarization")
        val hasDiarization = diarizationBundle != null && modelManager.isBundleReady(diarizationBundle)

        if (!hasDiarization) {
            // Inform user but CONTINUE
            Toast.makeText(context, "Speaker ID missing. Transcribing without speaker labels.", Toast.LENGTH_SHORT).show()
        }

        // --- 3. OPTIONAL CHECK: Enhancement ---
        // We check this inside the coroutine to decide whether to skip or run

        viewModelScope.launch(Dispatchers.IO) {
            recording.processingStatus = Recording.STATUS_PROCESSING
            recording.transcript = null
            recordingDao.update(recording)

            _transcriptionProgress.update {
                TranscriptionProgress(recordingId = recording.id, progress = 0f, message = "Initializing...")
            }

            val orchestrator = getOrchestrator()
            var pathToTranscribe = recording.filePath
            var tempEnhancedFile: File? = null
            var finalTranscript: String
            var finalStatus: Int

            try {
                // --- A. Enhancement (Skip if missing) ---
                val noiseBundle = ModelRegistry.getBundle("bundle_enhancement")
                val canEnhance = enhancementEnabled && noiseBundle != null && modelManager.isBundleReady(noiseBundle)

                if (canEnhance) {
                    _transcriptionProgress.update { it.copy(message = "Reducing noise...", progress = 0.05f) }
                    val enhancedPath = orchestrator.enhanceAudio(recording.filePath)
                    if (enhancedPath != null) {
                        pathToTranscribe = enhancedPath
                        tempEnhancedFile = File(enhancedPath)
                    }
                } else if (enhancementEnabled) {
                    // User wanted it, but files missing
                    Log.w("RecordingsViewModel", "Enhancement skipped: Models missing")
                }

                // --- B. Transcription ---
                _transcriptionProgress.update {
                    val msg = if(hasDiarization) "Transcribing & ID Speakers..." else "Transcribing (Simple)..."
                    it.copy(message = msg, progress = 0.1f)
                }

                val segments = orchestrator.transcribe(pathToTranscribe, language, modelName) { prog ->
                    _transcriptionProgress.update { it.copy(progress = 0.1f + (prog * 0.8f)) }
                }

                finalTranscript = if (segments.isNotEmpty()) {
                    if (hasDiarization) {
                        segments.joinToString("\n") { "Speaker ${it.speakerId}: ${it.text}" }
                    } else {
                        // Cleaner output for simple transcription
                        segments.joinToString("\n") { it.text }
                    }
                } else {
                    "No speech detected."
                }
                finalStatus = Recording.STATUS_COMPLETED

                // --- C. Search Indexing (Optional) ---
                if (finalStatus == Recording.STATUS_COMPLETED && finalTranscript.isNotBlank()) {
                    val searchBundle = ModelRegistry.getBundle("bundle_search")
                    if (searchBundle != null && modelManager.isBundleReady(searchBundle)) {
                        _transcriptionProgress.update { it.copy(message = "Indexing search...", progress = 0.95f) }
                        val vector = embeddingManager.generateEmbedding(finalTranscript)
                        recording.embedding = vector
                    }
                }

            } catch (e: Exception) {
                Log.e("RecordingsViewModel", "Transcription failed", e)
                finalTranscript = "[Error: ${e.localizedMessage}]"
                finalStatus = Recording.STATUS_FAILED
            } finally {
                tempEnhancedFile?.delete()
                _transcriptionProgress.update { TranscriptionProgress() }
            }

            recording.transcript = finalTranscript
            recording.processingStatus = finalStatus
            recordingDao.update(recording)
        }
    }

    fun performSemanticSearch(query: String) {
        if (query.isBlank()) {
            _searchResults.value = null
            return
        }

        val searchBundle = ModelRegistry.getBundle("bundle_search")
        if (searchBundle == null || !modelManager.isBundleReady(searchBundle)) return

        viewModelScope.launch(Dispatchers.Default) {
            val queryVector = embeddingManager.generateEmbedding(query) ?: return@launch
            val currentList = _allRecordingsInternal.value ?: emptyList()
            val results = currentList.mapNotNull { rec -> // 1. Rename 'uiState' to 'rec'
                // 2. Use 'rec' directly
                if (rec.embedding != null) {
                    val score = embeddingManager.cosineSimilarity(queryVector, rec.embedding!!)
                    if (score > 0.25) Pair(rec, score) else null
                } else null
            }
                .sortedByDescending { it.second }
                .map {
                    RecordingUiState(it.first) // 3. Wrap it in UiState at the end
                }

            _searchResults.value = results
        }
    }

    // ... (Rest of Player Logic unchanged) ...

    fun onPlayPauseClicked(recording: Recording) {
        val currentState = _playerState.value
        if (currentState.playingRecordingId == recording.id && currentState.isPlaying) {
            pausePlayback()
        } else {
            startPlayback(recording)
        }
    }

    private fun startPlayback(recording: Recording) {
        if (_playerState.value.playingRecordingId != recording.id) {
            stopPlayback()
            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(recording.filePath)
                    prepare()
                    setOnCompletionListener { stopPlayback() }
                } catch (e: Exception) {
                    e.printStackTrace()
                    stopPlayback()
                    return
                }
            }
            _playerState.update {
                it.copy(playingRecordingId = recording.id, maxDuration = recording.duration.toInt(), currentPosition = 0)
            }
        }
        mediaPlayer?.start()
        _playerState.update { it.copy(isPlaying = true) }
        startUpdatingProgress()
    }

    private fun pausePlayback() {
        mediaPlayer?.pause()
        _playerState.update { it.copy(isPlaying = false) }
        stopUpdatingProgress()
    }

    fun stopPlayback() {
        mediaPlayer?.release()
        mediaPlayer = null
        _playerState.value = PlayerState()
        stopUpdatingProgress()
    }

    fun onSeek(progress: Float) {
        mediaPlayer?.seekTo(progress.toInt())
        _playerState.update { it.copy(currentPosition = progress.toInt()) }
    }

    fun onRewind() {
        mediaPlayer?.let {
            val newPos = (it.currentPosition - 5000).coerceAtLeast(0)
            it.seekTo(newPos)
            _playerState.update { s -> s.copy(currentPosition = newPos) }
        }
    }

    fun onForward() {
        mediaPlayer?.let {
            val newPos = (it.currentPosition + 5000).coerceAtMost(_playerState.value.maxDuration)
            it.seekTo(newPos)
            _playerState.update { s -> s.copy(currentPosition = newPos) }
        }
    }

    private fun startUpdatingProgress() {
        stopUpdatingProgress()
        progressUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            while (_playerState.value.isPlaying) {
                mediaPlayer?.let {
                    _playerState.update { s -> s.copy(currentPosition = it.currentPosition) }
                }
                delay(250)
            }
        }
    }

    private fun stopUpdatingProgress() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    fun renameRecording(context: Context, recording: Recording) {
        stopPlayback()
        val editText = EditText(context).apply { setText(File(recording.filePath).nameWithoutExtension) }
        AlertDialog.Builder(context).setTitle("Rename").setView(editText).setPositiveButton("Rename") { _, _ ->
            val newName = editText.text.toString().trim()
            if (newName.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    val oldFile = File(recording.filePath)
                    val newFile = File(oldFile.parent, "$newName.wav")
                    if (oldFile.renameTo(newFile)) {
                        recording.filePath = newFile.absolutePath
                        recordingDao.update(recording)
                    }
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    fun deleteRecording(context: Context, recording: Recording) {
        AlertDialog.Builder(context).setTitle("Delete").setMessage("Confirm delete?").setPositiveButton("Delete") { _, _ ->
            if (recording.id == _playerState.value.playingRecordingId) stopPlayback()
            viewModelScope.launch(Dispatchers.IO) {
                File(recording.filePath).delete()
                recordingDao.delete(recording)
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun combineRecordingAndProgress(recordings: List<Recording>, progress: TranscriptionProgress): List<RecordingUiState> {
        return recordings.map { rec ->
            if (rec.id == progress.recordingId) RecordingUiState(rec, progress.progress, progress.message) else RecordingUiState(rec)
        }
    }

    fun bindService(context: Context) {
        Intent(context, RecordingService::class.java).also { intent -> context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
    }
    fun unbindService(context: Context) { if (isBound) { context.unbindService(connection); isBound = false; recordingService = null } }
    fun toggleRecordingService(context: Context) {
        val intent = Intent(context, RecordingService::class.java)
        if (RecordingService.isRecording) { intent.action = RecordingService.ACTION_STOP; context.startService(intent) }
        else { context.startService(intent) }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        transcriptionOrchestrator?.close()
        embeddingManager.close()
        unbindService(getApplication())
    }

    data class PlayerState(val playingRecordingId: Long? = null, val isPlaying: Boolean = false, val currentPosition: Int = 0, val maxDuration: Int = 0)
}