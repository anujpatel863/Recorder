package com.example.allrecorder.recordings

import android.app.Application
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.allrecorder.*
import com.example.allrecorder.data.RecordingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context
import android.content.ServiceConnection
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.widget.EditText
import androidx.annotation.RequiresApi

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    application: Application,
    private val repository: RecordingsRepository
) : AndroidViewModel(application) {

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

    // --- DATA: Amplitudes & Speeds ---
    private val amplitudeCache = mutableMapOf<Long, List<Int>>()
    private val playbackSpeeds = mutableMapOf<Long, Float>()
    private val _uiRefreshTrigger = MutableLiveData<Unit>()

    private data class TranscriptionProgress(
        val recordingId: Long? = null,
        val progress: Float = 0f,
        val message: String = ""
    )

    data class RecordingUiState(
        val recording: Recording,
        val liveProgress: Float? = null,
        val liveMessage: String? = null,
        val isSemanticMatch: Boolean = false,
        val amplitudes: List<Int> = emptyList(),
        val playbackSpeed: Float = 1.0f
    )

    private val _transcriptionProgress = MutableStateFlow(TranscriptionProgress())
    private val _transcriptionProgressLiveData = _transcriptionProgress.asLiveData()

    // --- All Recordings Sources ---
    private val _allRecordingsInternal = repository.getAllRecordings().asLiveData()
    val allRecordings: MediatorLiveData<List<RecordingUiState>> = MediatorLiveData()

    // --- Starred Recordings Sources ---
    private val _starredRecordingsInternal = repository.getStarredRecordings().asLiveData()
    val starredRecordings: MediatorLiveData<List<RecordingUiState>> = MediatorLiveData()

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
        val updateAll = {
            val recs = _allRecordingsInternal.value ?: emptyList()
            val prog = _transcriptionProgressLiveData.value ?: TranscriptionProgress()
            allRecordings.value = combineRecordingAndProgress(recs, prog)
        }
        val updateStarred = {
            val recs = _starredRecordingsInternal.value ?: emptyList()
            val prog = _transcriptionProgressLiveData.value ?: TranscriptionProgress()
            starredRecordings.value = combineRecordingAndProgress(recs, prog)
        }

        allRecordings.addSource(_allRecordingsInternal) { updateAll() }
        allRecordings.addSource(_transcriptionProgressLiveData) { updateAll() }
        allRecordings.addSource(_uiRefreshTrigger) { updateAll() }

        starredRecordings.addSource(_starredRecordingsInternal) { updateStarred() }
        starredRecordings.addSource(_transcriptionProgressLiveData) { updateStarred() }
        starredRecordings.addSource(_uiRefreshTrigger) { updateStarred() }

        viewModelScope.launch {
            while(true) {
                if (isServiceRecording != RecordingService.isRecording) {
                    isServiceRecording = RecordingService.isRecording
                }
                delay(500)
            }
        }
    }

    // --- REPO DELEGATION ---
    fun loadAmplitudes(recording: Recording) {
        if (amplitudeCache.containsKey(recording.id)) return
        viewModelScope.launch {
            val amps = repository.loadAmplitudes(recording)
            synchronized(amplitudeCache) { amplitudeCache[recording.id] = amps }
            _uiRefreshTrigger.postValue(Unit)
        }
    }

    fun renameRecording(context: Context, recording: Recording) {
        stopPlayback()
        val editText = EditText(context).apply { setText(java.io.File(recording.filePath).nameWithoutExtension) }
        AlertDialog.Builder(context).setTitle("Rename").setView(editText).setPositiveButton("Rename") { _, _ ->
            val newName = editText.text.toString().trim()
            if (newName.isNotEmpty()) {
                viewModelScope.launch {
                    repository.renameRecording(recording, newName)
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    fun deleteRecording(context: Context, recording: Recording) {
        AlertDialog.Builder(context).setTitle("Delete").setMessage("Confirm delete?").setPositiveButton("Delete") { _, _ ->
            if (recording.id == _playerState.value.playingRecordingId) stopPlayback()
            viewModelScope.launch {
                repository.deleteRecording(recording)
            }
        }.setNegativeButton("Cancel", null).show()
    }

    fun toggleStar(recording: Recording) {
        viewModelScope.launch {
            repository.toggleStar(recording)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveRecordingAs(context: Context, recording: Recording) {
        viewModelScope.launch {
            repository.saveRecordingAs(recording)
        }
    }

    fun transcribeRecording(context: Context, recording: Recording) {
        viewModelScope.launch {
            _transcriptionProgress.value = TranscriptionProgress(recording.id, 0f, "Starting...")
            repository.transcribeRecording(recording) { progress, message ->
                _transcriptionProgress.value = TranscriptionProgress(recording.id, progress, message)
            }
            // Clear progress after short delay when done
            delay(2000)
            _transcriptionProgress.value = TranscriptionProgress()
        }
    }

    fun performSemanticSearch(query: String) {
        if (query.isBlank()) {
            _searchResults.value = null
            return
        }
        viewModelScope.launch {
            val results = repository.performSemanticSearch(query)
            _searchResults.value = results.map { rec ->
                RecordingUiState(
                    recording = rec,
                    isSemanticMatch = true,
                    amplitudes = amplitudeCache[rec.id] ?: emptyList(),
                    playbackSpeed = playbackSpeeds[rec.id] ?: 1.0f
                )
            }
        }
    }

    // --- PLAYBACK CONTROLS (Kept in ViewModel as it drives UI state directly) ---

    fun onSeek(recording: Recording, progress: Float) {
        val currentId = _playerState.value.playingRecordingId
        val newPos = progress.toInt()

        if (currentId != recording.id) {
            mediaPlayer?.release()
            mediaPlayer = null
            _playerState.update {
                PlayerState(
                    playingRecordingId = recording.id,
                    isPlaying = false,
                    currentPosition = newPos,
                    maxDuration = recording.duration.toInt()
                )
            }
        } else {
            _playerState.update { it.copy(currentPosition = newPos) }
            mediaPlayer?.seekTo(newPos)
        }
    }

    fun togglePlaybackSpeed(recording: Recording) {
        val currentSpeed = playbackSpeeds[recording.id] ?: 1.0f
        val newSpeed = when (currentSpeed) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            2.0f -> 0.5f
            else -> 1.0f
        }
        playbackSpeeds[recording.id] = newSpeed
        if (_playerState.value.playingRecordingId == recording.id) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    mediaPlayer?.let { it.playbackParams = it.playbackParams.setSpeed(newSpeed) }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        _uiRefreshTrigger.value = Unit
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
        val isResume = _playerState.value.playingRecordingId == recording.id
        if (!isResume) stopPlayback()

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(recording.filePath)
                prepare()
                if (isResume && _playerState.value.currentPosition > 0) {
                    seekTo(_playerState.value.currentPosition)
                }
                val speedToUse = playbackSpeeds[recording.id] ?: 1.0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    playbackParams = playbackParams.setSpeed(speedToUse)
                }
                setOnCompletionListener { stopPlayback() }
            } catch (e: Exception) {
                e.printStackTrace()
                stopPlayback()
                return
            }
        }

        if (!isResume) {
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

    fun onRewind() {
        val current = _playerState.value
        if (current.playingRecordingId == null) return
        val newPos = (current.currentPosition - 5000).coerceAtLeast(0)
        mediaPlayer?.seekTo(newPos)
        _playerState.update { it.copy(currentPosition = newPos) }
    }

    fun onForward() {
        val current = _playerState.value
        if (current.playingRecordingId == null) return
        val newPos = (current.currentPosition + 5000).coerceAtMost(current.maxDuration)
        mediaPlayer?.seekTo(newPos)
        _playerState.update { it.copy(currentPosition = newPos) }
    }

    private fun startUpdatingProgress() {
        stopUpdatingProgress()
        progressUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            while (_playerState.value.isPlaying) {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        _playerState.update { s -> s.copy(currentPosition = it.currentPosition) }
                    }
                }
                delay(50)
            }
        }
    }

    private fun stopUpdatingProgress() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    private fun combineRecordingAndProgress(recordings: List<Recording>, progress: TranscriptionProgress): List<RecordingUiState> {
        return recordings.map { rec ->
            val amps = amplitudeCache[rec.id] ?: emptyList()
            val speed = playbackSpeeds[rec.id] ?: 1.0f
            if (rec.id == progress.recordingId)
                RecordingUiState(rec, progress.progress, progress.message, amplitudes = amps, playbackSpeed = speed)
            else
                RecordingUiState(rec, amplitudes = amps, playbackSpeed = speed)
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
        unbindService(getApplication())
    }

    data class PlayerState(val playingRecordingId: Long? = null, val isPlaying: Boolean = false, val currentPosition: Int = 0, val maxDuration: Int = 0)
}