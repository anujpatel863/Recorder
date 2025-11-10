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
    private val transcriptionOrchestrator = TranscriptionOrchestrator(application)

    val allRecordings: LiveData<List<Recording>> = recordingDao.getAllRecordings().asLiveData()

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
        viewModelScope.launch {
            while(true) {
                if (isServiceRecording != RecordingService.isRecording) {
                    isServiceRecording = RecordingService.isRecording
                }
                delay(500)
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

    fun transcribeRecording(context: Context, recording: Recording) {
        Toast.makeText(context, "Starting transcription... this may take a while.", Toast.LENGTH_LONG).show()
        viewModelScope.launch(Dispatchers.IO) {
            val language = SettingsManager.asrLanguage

            val finalTranscript = try {
                val segments = transcriptionOrchestrator.transcribe(recording.filePath, language)
                if (segments.isNotEmpty()) {
                    segments.joinToString("\n") { segment ->
                        "Speaker ${segment.speakerId}: ${segment.text}"
                    }
                } else {
                    "No speech detected or transcription failed."
                }
            } catch (e: Exception) {
                Log.e("RecordingsViewModel", "Transcription failed", e)
                "[Transcription Failed: ${e.message}]"
            }

            recording.transcript = finalTranscript
            recording.isProcessed = true
            recordingDao.update(recording)

            launch(Dispatchers.Main) {
                Toast.makeText(context, "Transcription complete!", Toast.LENGTH_SHORT).show()
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        transcriptionOrchestrator.close()
        getApplication<Application>().let { unbindService(it) }
    }
}