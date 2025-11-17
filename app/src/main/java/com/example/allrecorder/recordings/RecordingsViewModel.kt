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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.allrecorder.*
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

    private var transcriptionOrchestrator: TranscriptionOrchestrator? = null

    var isServiceRecording by mutableStateOf(RecordingService.isRecording)
        private set

    private var mediaPlayer: MediaPlayer? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var progressUpdateJob: Job? = null

    // --- VISUALIZER ---
    private val _audioData = MutableStateFlow(ByteArray(0))
    val audioData: StateFlow<ByteArray> = _audioData.asStateFlow()

    private var recordingService: RecordingService? = null
    private var isBound by mutableStateOf(false)
    private data class TranscriptionProgress(
        val recordingId: Long? = null,
        val progress: Float = 0f, // 0.0 to 1.0
        val message: String = ""
    )

    /**
     * This data class is what the UI will observe. It combines the
     * recording from the database with any live progress state.
     */
    data class RecordingUiState(
        val recording: Recording,
        // Live progress (0.0 - 1.0)
        val liveProgress: Float? = null,
        // Message for live progress
        val liveMessage: String? = null
    )

    // Internal state flow to hold the *current* transcription progress
    private val _transcriptionProgress = MutableStateFlow(TranscriptionProgress())

    // Internal LiveData sources
    private val _allRecordingsInternal = recordingDao.getAllRecordings().asLiveData()
    private val _transcriptionProgressLiveData = _transcriptionProgress.asLiveData()

    /**
     * This is the public LiveData the UI should observe.
     * It combines the list of all recordings with the live progress
     * of the item currently being transcribed.
     */
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

    fun bindService(context: Context) {
        Intent(context, RecordingService::class.java).also { intent ->
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }
    private fun getOrchestrator(): TranscriptionOrchestrator {
        // If it's already created, just return it
        transcriptionOrchestrator?.let { return it }

        // If not, create it. This will run loadModels()
        // on the current background thread.
        Log.i("RecordingsViewModel", "Creating new TranscriptionOrchestrator...")
        val newOrchestrator = TranscriptionOrchestrator(getApplication())
        transcriptionOrchestrator = newOrchestrator
        return newOrchestrator
    }

    fun unbindService(context: Context) {
        if (isBound) {
            context.unbindService(connection)
            isBound = false
            recordingService = null
        }
    }
    // --- END VISUALIZER ---

    data class PlayerState(
        val playingRecordingId: Long? = null,
        val isPlaying: Boolean = false,
        val currentPosition: Int = 0,
        val maxDuration: Int = 0
    )

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
    private fun combineRecordingAndProgress(
        recordings: List<Recording>,
        progress: TranscriptionProgress
    ): List<RecordingUiState> {
        return recordings.map { rec ->
            if (rec.id == progress.recordingId) {
                // This is the item being processed. Attach progress to it.
                RecordingUiState(
                    recording = rec,
                    liveProgress = progress.progress,
                    liveMessage = progress.message
                )
            } else {
                // This item is not being processed.
                RecordingUiState(recording = rec)
            }
        }
    }

    fun toggleRecordingService(context: Context) {
        val intent = Intent(context, RecordingService::class.java)
        if (RecordingService.isRecording) {
            intent.action = RecordingService.ACTION_STOP
            context.startService(intent)
        } else {
            context.startService(intent)
        }
    }

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
                it.copy(
                    playingRecordingId = recording.id,
                    maxDuration = recording.duration.toInt(),
                    currentPosition = 0
                )
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
        val editText = EditText(context).apply {
            setText(File(recording.filePath).nameWithoutExtension)
        }

        AlertDialog.Builder(context)
            .setTitle("Rename Recording")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
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
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun deleteRecording(context: Context, recording: Recording) {
        AlertDialog.Builder(context)
            .setTitle("Delete Recording")
            .setMessage("Are you sure you want to permanently delete this recording?")
            .setPositiveButton("Delete") { _, _ ->
                if (recording.id == _playerState.value.playingRecordingId) {
                    stopPlayback()
                }
                viewModelScope.launch(Dispatchers.IO) {
                    File(recording.filePath).delete()
                    recordingDao.delete(recording)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- FUNCTION MODIFIED FOR LIVE PROGRESS ---
    fun transcribeRecording(context: Context, recording: Recording) {

        // Don't start a new job if one is already running for this item
        if (recording.processingStatus == Recording.STATUS_PROCESSING) {
            Toast.makeText(context, "Already processing...", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            // --- 1. SET "PROCESSING" STATE IN DB ---
            // This updates the database, which triggers the _allRecordingsInternal LiveData
            recording.processingStatus = Recording.STATUS_PROCESSING
            recording.transcript = null // Clear old transcript if any
            recordingDao.update(recording)

            // --- 2. SET "PROCESSING" STATE IN LIVE FLOW ---
            // This triggers the _transcriptionProgressLiveData
            _transcriptionProgress.update {
                TranscriptionProgress(
                    recordingId = recording.id,
                    progress = 0f,
                    message = "Starting..."
                )
            }

            // Get settings
            val language = SettingsManager.asrLanguage
            val modelName = SettingsManager.asrModel
            val enhancementEnabled = SettingsManager.asrEnhancementEnabled

            val orchestrator = getOrchestrator()
            var pathToTranscribe = recording.filePath
            var tempEnhancedFile: File? = null

            var finalTranscript: String
            var finalStatus: Int

            try {
                if (enhancementEnabled) {
                    _transcriptionProgress.update {
                        it.copy(
                            message = "Reducing noise... (Step 1 of 2)",
                            progress = 0.05f // Show a little progress
                        )
                    }
                    val enhancedPath = orchestrator.enhanceAudio(recording.filePath)

                    if (enhancedPath != null) {
                        pathToTranscribe = enhancedPath
                        tempEnhancedFile = File(enhancedPath)
                        _transcriptionProgress.update {
                            it.copy(
                                message = "Noise reduction complete.",
                                progress = 0.1f // 10% complete
                            )
                        }
                    } else {
                        Log.e("RecordingsViewModel", "Enhancement failed, transcribing original.")
                        _transcriptionProgress.update {
                            it.copy(
                                message = "Enhancement failed.",
                                progress = 0.1f // Skip to 10%
                            )
                        }
                    }
                } else {
                    // No enhancement, jump to 10%
                    _transcriptionProgress.update { it.copy(progress = 0.1f) }
                }

                _transcriptionProgress.update {
                    val step = if (enhancementEnabled) "(Step 2 of 2)" else ""
                    it.copy(message = "Transcribing... $step")
                }

                // --- 3. CALL ORCHESTRATOR WITH PROGRESS CALLBACK ---
                val segments = orchestrator.transcribe(
                    pathToTranscribe,
                    language,
                    modelName
                ) { segmentProgress ->
                    // This is our callback
                    // segmentProgress is 0.0 to 1.0
                    // We map this to our 0.1 (10%) to 1.0 (100%) range
                    val totalProgress = 0.1f + (segmentProgress * 0.9f)
                    _transcriptionProgress.update {
                        it.copy(progress = totalProgress)
                    }
                }

                finalTranscript = if (segments.isNotEmpty()) {
                    segments.joinToString("\n") { segment ->
                        "Speaker ${segment.speakerId}: ${segment.text}"
                    }
                } else {
                    "No speech detected or transcription failed."
                }
                finalStatus = Recording.STATUS_COMPLETED

            } catch (e: Exception) {
                Log.e("RecordingsViewModel", "Transcription failed", e)
                finalTranscript = "[Transcription Failed: ${e.message}]"
                finalStatus = Recording.STATUS_FAILED
            } finally {
                tempEnhancedFile?.let {
                    if (it.exists()) {
                        it.delete()
                        Log.i("RecordingsViewModel", "Cleaned up temp enhanced file.")
                    }
                }

                // --- 4. CLEAR LIVE PROGRESS STATE ---
                // This resets the progress bar.
                _transcriptionProgress.update { TranscriptionProgress() }
            }

            // --- 5. UPDATE DB WITH FINAL RESULT ---
            // This updates the DB with the final transcript and status.
            recording.transcript = finalTranscript
            recording.processingStatus = finalStatus
            recordingDao.update(recording)

            launch(Dispatchers.Main) {
                if (finalStatus == Recording.STATUS_COMPLETED) {
                    Toast.makeText(context, "Transcription complete!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Transcription failed.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        stopPlayback()

        transcriptionOrchestrator?.close()
        transcriptionOrchestrator = null

        getApplication<Application>().let { unbindService(it) }
    }
}