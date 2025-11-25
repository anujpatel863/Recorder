package com.example.allrecorder.recordings

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.widget.EditText
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.allrecorder.*
import com.example.allrecorder.data.RecordingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.File
import javax.inject.Inject
import androidx.annotation.RequiresPermission


@HiltViewModel
class RecordingsViewModel @Inject constructor(
    application: Application,
    private val repository: RecordingsRepository
) : AndroidViewModel(application) {

    var isServiceRecording by mutableStateOf(RecordingService.isRecording)
        private set

    private var recordingService: RecordingService? = null
    private var isBound by mutableStateOf(false)

    private val _audioData = MutableStateFlow(ByteArray(0))
    val audioData: StateFlow<ByteArray> = _audioData.asStateFlow()

    private val _formattedDuration = MutableStateFlow("00:00")
    val formattedDuration: StateFlow<String> = _formattedDuration.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            isBound = true
            viewModelScope.launch {
                recordingService?.audioDataFlow?.collectLatest { _audioData.value = it }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            recordingService = null
        }
    }

    // --- PLAYBACK STATE ---
    private var mediaPlayer: MediaPlayer? = null
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    private var progressUpdateJob: Job? = null

    // Cache
    private val amplitudeCache = mutableMapOf<Long, List<Int>>()
    private val playbackSpeeds = mutableMapOf<Long, Float>()

    // --- FILTER & SEARCH STATE ---
    private val _tagFilter = MutableStateFlow<String?>(null)
    val tagFilter: StateFlow<String?> = _tagFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _rawSemanticResults = MutableStateFlow<List<Recording>?>(null)

    // --- UI UPDATES ---
    // [FIX] Dedicated trigger for amplitude updates to ensure StateFlow emits a change
    private val _amplitudeUpdateTrigger = MutableStateFlow(0)

    private data class TranscriptionProgress(
        val recordingId: Long? = null,
        val progress: Float = 0f,
        val message: String = ""
    )
    private val _transcriptionProgress = MutableStateFlow(TranscriptionProgress())

    // [NEW] Re-indexing State
    var showReindexDialog by mutableStateOf(false)
    var reindexProgress by mutableStateOf(0f)
    var isReindexing by mutableStateOf(false)

    // 1. All Recordings
    val allRecordings = combine(
        repository.getAllRecordings(),
        _tagFilter,
        _transcriptionProgress,
        _amplitudeUpdateTrigger
    ) { recordings, tag, progress, _ ->
        val filtered = if (tag != null) recordings.filter { it.tags.contains(tag) } else recordings
        combineRecordingAndProgress(filtered, progress)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableTags: StateFlow<List<String>> = allRecordings.map { list ->
        list.flatMap { it.recording.tags }
            .distinct()
            .sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 2. Starred Recordings
    val starredRecordings = combine(
        repository.getStarredRecordings(),
        _tagFilter,
        _transcriptionProgress,
        _amplitudeUpdateTrigger
    ) { recordings, tag, progress, _ ->
        val filtered = if (tag != null) recordings.filter { it.tags.contains(tag) } else recordings
        combineRecordingAndProgress(filtered, progress)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 3. Search Results
    val searchResults: StateFlow<List<RecordingUiState>?> = combine(
        _rawSemanticResults,
        _tagFilter,
        _transcriptionProgress,
        _amplitudeUpdateTrigger
    ) { rawResults, tag, progress, _ ->
        if (rawResults == null) return@combine null

        val filtered = if (tag != null) rawResults.filter { it.tags.contains(tag) } else rawResults
        combineRecordingAndProgress(filtered, progress).map { it.copy(isSemanticMatch = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)


    init {
        // Monitor Service State
        viewModelScope.launch {
            while(true) {
                val recording = RecordingService.isRecording
                if (isServiceRecording != recording) {
                    isServiceRecording = recording
                }

                if (recording && recordingService != null) {
                    val durationMillis = System.currentTimeMillis() - recordingService!!.recordingStartTime
                    _formattedDuration.value = formatDuration(durationMillis)
                } else if (recording) {
                    _formattedDuration.value = "Starting..."
                } else {
                    _formattedDuration.value = "Start Recording"
                }
                delay(500)
            }
        }
        checkConsistency()
    }

    fun checkConsistency() {
        viewModelScope.launch {
            if (SettingsManager.semanticSearchEnabled) {
                val count = repository.getMissingEmbeddingsCount()
                if (count > 0) {
                    showReindexDialog = true
                }
            }
        }
    }

    fun startReindexing() {
        showReindexDialog = false
        isReindexing = true
        viewModelScope.launch {
            repository.reindexAllMissing { current, total ->
                reindexProgress = if (total > 0) current.toFloat() / total.toFloat() else 0f
            }
            isReindexing = false
            reindexProgress = 0f
        }
    }

    fun dismissReindexDialog() {
        showReindexDialog = false
    }

    fun setTagFilter(tag: String?) {
        _tagFilter.value = tag
    }

    fun addTag(recording: Recording, newTag: String) {
        if (newTag.isBlank() || recording.tags.contains(newTag)) return
        viewModelScope.launch(Dispatchers.IO) {
            val updatedTags = recording.tags + newTag.trim()
            repository.updateRecording(recording.copy(tags = updatedTags))
        }
    }

    fun removeTag(recording: Recording, tagToRemove: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedTags = recording.tags - tagToRemove
            repository.updateRecording(recording.copy(tags = updatedTags))
        }
    }

    // --- SEARCH LOGIC ---

    fun performSemanticSearch(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _rawSemanticResults.value = null
            return
        }
        viewModelScope.launch {
            if (SettingsManager.semanticSearchEnabled) checkConsistency()
            val results = repository.performSemanticSearch(query)
            _rawSemanticResults.value = results
        }
    }

    // --- REPO DELEGATION ---
    fun loadAmplitudes(recording: Recording) {
        if (amplitudeCache.containsKey(recording.id)) return
        viewModelScope.launch {
            val amps = repository.loadAmplitudes(recording)
            synchronized(amplitudeCache) { amplitudeCache[recording.id] = amps }
            // [FIX] Increment trigger to force UI update immediately
            _amplitudeUpdateTrigger.value += 1
        }
    }

    fun renameRecording(context: Context, recording: Recording) {
        stopPlayback()
        val editText = EditText(context).apply { setText(File(recording.filePath).nameWithoutExtension) }
        AlertDialog.Builder(context).setTitle("Rename").setView(editText).setPositiveButton("Rename") { _, _ ->
            val newName = editText.text.toString().trim()
            if (newName.isNotEmpty()) {
                viewModelScope.launch { repository.renameRecording(recording, newName) }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    fun deleteRecording(context: Context, recording: Recording) {
        AlertDialog.Builder(context).setTitle("Delete").setMessage("Confirm delete?").setPositiveButton("Delete") { _, _ ->
            if (recording.id == _playerState.value.playingRecordingId) stopPlayback()
            viewModelScope.launch { repository.deleteRecording(recording) }
        }.setNegativeButton("Cancel", null).show()
    }

    fun toggleStar(recording: Recording) {
        viewModelScope.launch { repository.toggleStar(recording) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveRecordingAs(context: Context, recording: Recording) {
        viewModelScope.launch { repository.saveRecordingAs(recording) }
    }

    fun transcribeRecording(context: Context, recording: Recording) {
        viewModelScope.launch {
            _transcriptionProgress.value = TranscriptionProgress(recording.id, 0f, "Starting...")
            repository.transcribeRecording(recording) { progress, message ->
                _transcriptionProgress.value = TranscriptionProgress(recording.id, progress, message)
            }
            delay(2000)
            _transcriptionProgress.value = TranscriptionProgress()
        }
    }

    // --- PLAYBACK CONTROLS ---

    fun onSeek(recording: Recording, progress: Float) {
        val currentId = _playerState.value.playingRecordingId
        val newPos = progress.toInt()

        if (currentId != recording.id) {
            mediaPlayer?.release()
            mediaPlayer = null
            _playerState.update {
                PlayerState(playingRecordingId = recording.id, isPlaying = false, currentPosition = newPos, maxDuration = recording.duration.toInt())
            }
        } else {
            _playerState.update { it.copy(currentPosition = newPos) }
            mediaPlayer?.seekTo(newPos)
        }
    }

    fun togglePlaybackSpeed(recording: Recording) {
        val currentSpeed = playbackSpeeds[recording.id] ?: 1.0f
        val newSpeed = when (currentSpeed) {
            1.0f -> 1.5f; 1.5f -> 2.0f; 2.0f -> 0.5f; else -> 1.0f
        }
        playbackSpeeds[recording.id] = newSpeed

        if (_playerState.value.playingRecordingId == recording.id) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { mediaPlayer?.let { it.playbackParams = it.playbackParams.setSpeed(newSpeed) } } catch (e: Exception) {}
            }
        }
        _transcriptionProgress.value = _transcriptionProgress.value.copy()
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
                if (isResume && _playerState.value.currentPosition > 0) seekTo(_playerState.value.currentPosition)

                val speedToUse = playbackSpeeds[recording.id] ?: 1.0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) playbackParams = playbackParams.setSpeed(speedToUse)

                setOnCompletionListener { stopPlayback() }
            } catch (e: Exception) {
                e.printStackTrace()
                stopPlayback()
                return
            }
        }

        if (!isResume) {
            _playerState.update { it.copy(playingRecordingId = recording.id, maxDuration = recording.duration.toInt(), currentPosition = 0) }
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
        stopUpdatingProgress()
        _playerState.value = PlayerState()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }
        mediaPlayer = null
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
            while (isActive && _playerState.value.isPlaying) {
                try {
                    mediaPlayer?.let {
                        if (it.isPlaying) {
                            val pos = it.currentPosition
                            _playerState.update { s -> s.copy(currentPosition = pos) }
                        }
                    }
                } catch (e: IllegalStateException) {
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
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

    fun updateTranscript(recording: Recording, newText: String) {
        viewModelScope.launch {
            repository.updateTranscript(recording, newText)
        }
    }

    fun exportTranscript(context: Context, recording: Recording, format: TranscriptExporter.Format) {
        viewModelScope.launch(Dispatchers.IO) {
            TranscriptExporter.export(context, recording, format)
        }
    }

    fun bindService(context: Context) { Intent(context, RecordingService::class.java).also { intent -> context.bindService(intent, connection, Context.BIND_AUTO_CREATE) } }
    fun unbindService(context: Context) { if (isBound) { context.unbindService(connection); isBound = false; recordingService = null } }
    @RequiresPermission(Manifest.permission.VIBRATE)
    fun toggleRecordingService(context: Context) {
        val intent = Intent(context, RecordingService::class.java)
        if (RecordingService.isRecording) { intent.action = RecordingService.ACTION_STOP; context.startService(intent) }
        else { context.startService(intent) }
        if (SettingsManager.hapticFeedback) {
            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (vibrator?.hasVibrator() == true) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        if (isBound) try { getApplication<Application>().unbindService(connection) } catch(e:Exception){}
    }

    data class PlayerState(val playingRecordingId: Long? = null, val isPlaying: Boolean = false, val currentPosition: Int = 0, val maxDuration: Int = 0)
    data class RecordingUiState(
        val recording: Recording,
        val liveProgress: Float? = null,
        val liveMessage: String? = null,
        val isSemanticMatch: Boolean = false,
        val amplitudes: List<Int> = emptyList(),
        val playbackSpeed: Float = 1.0f
    )
}