package com.example.allrecorder.recordings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.allrecorder.Recording
import com.example.allrecorder.RecordingService
import com.example.allrecorder.SettingsManager
import com.example.allrecorder.TranscriptExporter
import com.example.allrecorder.data.RecordingsRepository
import com.example.allrecorder.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    application: Application,
    private val repository: RecordingsRepository,
    private val playerManager: AudioPlayerManager // [IMPROVED] Injected Manager
) : AndroidViewModel(application) {

    var isServiceRecording by mutableStateOf(RecordingService.isRecording)

    @SuppressLint("StaticFieldLeak")
    private var recordingService: RecordingService? = null
    private var isBound by mutableStateOf(false)

    private val _audioData = MutableStateFlow(ByteArray(0))
    val audioData: StateFlow<ByteArray> = _audioData.asStateFlow()

    private val _formattedDuration = MutableStateFlow("00:00")
    private val _showCallRecordings = MutableStateFlow(SettingsManager.showCallRecordings)
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
    // [IMPROVED] State now comes directly from the Manager
    val playerState: StateFlow<AudioPlayerManager.PlayerState> = playerManager.playerState

    // Cache for visualization
    private val amplitudeCache = mutableMapOf<Long, List<Int>>()

    // [FIX] Dedicated trigger for amplitude updates to ensure StateFlow emits a change
    private val _amplitudeUpdateTrigger = MutableStateFlow(0)

    // --- FILTER & SEARCH STATE ---
    private val _tagFilter = MutableStateFlow<String?>(null)
    val tagFilter: StateFlow<String?> = _tagFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _rawSemanticResults = MutableStateFlow<List<Recording>?>(null)

    private data class TranscriptionProgress(
        val recordingId: Long? = null,
        val progress: Float = 0f,
        val message: String = ""
    )
    private val _transcriptionProgress = MutableStateFlow(TranscriptionProgress())

    // [NEW] Re-indexing State
    var showReindexDialog by mutableStateOf(false)
    var reindexProgress by mutableFloatStateOf(0f)
    var isReindexing by mutableStateOf(false)

    // 1. All Recordings
    val allRecordings = combine(
        repository.getAllRecordings(),
        _tagFilter,
        _transcriptionProgress,
        _amplitudeUpdateTrigger,
        _showCallRecordings
    ) { recordings, tag, progress, _, showCalls ->
        // 1. Filter by tag
        var filtered = if (tag != null) recordings.filter { it.tags.contains(tag) } else recordings

        // 2. Filter by "Show Calls" Setting
        if (!showCalls) {
            filtered = filtered.filter { !it.tags.contains("imported") }
        }

        // 3. Sort by Time & Date (Descending)
        val sorted = filtered.sortedByDescending { it.startTime }

        combineRecordingAndProgress(sorted, progress)
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
    fun updateShowCallRecordings(enabled: Boolean, context: Context) {
        _showCallRecordings.value = enabled
        SettingsManager.showCallRecordings = enabled
        if (enabled) {
            syncCallRecordings(context)
        }
    }
    fun syncCallRecordings(context: Context) {
        viewModelScope.launch {
            try {
                repository.importExternalRecordings { count ->
                    // Optional: Notify only if new ones found or generic success
                    if (count > 0) {
                        viewModelScope.launch(Dispatchers.Main) {
                            Toast.makeText(context, "Found $count new recordings.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
            _amplitudeUpdateTrigger.value += 1
        }
    }

    fun deleteRecording(context: Context, recording: Recording) {
        AlertDialog.Builder(context).setTitle("Delete").setMessage("Confirm delete?").setPositiveButton("Delete") { _, _ ->
            if (recording.id == playerState.value.playingRecordingId) stopPlayback()
            viewModelScope.launch { repository.deleteRecording(recording) }
        }.setNegativeButton("Cancel", null).show()
    }

    fun toggleStar(recording: Recording) {
        viewModelScope.launch { repository.toggleStar(recording) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveRecordingAs(recording: Recording) {
        viewModelScope.launch { repository.saveRecordingAs(recording) }
    }

    fun transcribeRecording(recording: Recording) {
        viewModelScope.launch {
            _transcriptionProgress.value = TranscriptionProgress(recording.id, 0f, "Starting...")
            repository.transcribeRecording(recording) { progress, message ->
                _transcriptionProgress.value = TranscriptionProgress(recording.id, progress, message)
            }
            delay(2000)
            _transcriptionProgress.value = TranscriptionProgress()
        }
    }

    // --- PLAYBACK CONTROLS (DELEGATED TO MANAGER) ---

    fun onSeek(recording: Recording, progress: Float) {
        val duration = recording.duration.toInt()
        val targetMs = (progress * duration).toInt()

        // If we are seeking a different recording than currently playing, start it first
        if (playerState.value.playingRecordingId != recording.id) {
            playerManager.startPlayback(recording.id, recording.filePath, recording.duration)
            playerManager.pausePlayback() // Start then pause to verify user intent or just play?
            // Usually onSeek implies dragging, so we seek.
        }
        playerManager.seekTo(targetMs)
    }

    fun togglePlaybackSpeed() {
        playerManager.toggleSpeed()
    }

    fun onPlayPauseClicked(recording: Recording) {
        val currentState = playerState.value
        if (currentState.playingRecordingId == recording.id && currentState.isPlaying) {
            playerManager.pausePlayback()
        } else {
            playerManager.startPlayback(recording.id, recording.filePath, recording.duration)
        }
    }

    fun stopPlayback() {
        playerManager.stopPlayback()
    }

    fun onRewind() {
        val current = playerState.value
        if (current.playingRecordingId == null) return
        val newPos = (current.currentPosition - 5000).coerceAtLeast(0)
        playerManager.seekTo(newPos)
    }

    fun onForward() {
        val current = playerState.value
        if (current.playingRecordingId == null) return
        val newPos = (current.currentPosition + 5000).coerceAtMost(current.maxDuration)
        playerManager.seekTo(newPos)
    }

    // Helper to combine data (removed manual MediaPlayer checks)
    private fun combineRecordingAndProgress(recordings: List<Recording>, progress: TranscriptionProgress): List<RecordingUiState> {
        return recordings.map { rec ->
            val isPlaying = playerState.value.playingRecordingId == rec.id
            val speed = if (isPlaying) playerState.value.playbackSpeed else 1.0f
            RecordingUiState(
                recording = rec,
                liveProgress = if (rec.id == progress.recordingId) progress.progress else null,
                liveMessage = if (rec.id == progress.recordingId) progress.message else null,
                amplitudes = amplitudeCache[rec.id] ?: emptyList(),
                playbackSpeed = speed
            )
        }
    }

    fun exportTranscript(context: Context, recording: Recording, format: TranscriptExporter.Format) {
        viewModelScope.launch(Dispatchers.IO) {
            TranscriptExporter.export(context, recording, format)
        }
    }

    fun duplicateRecording(recording: Recording, tagToAdd: String? = null) {
        viewModelScope.launch {
            repository.duplicateRecording(recording, tagToAdd)
        }
    }

    // [IMPROVED] Robust Save As with Duplicate Name Checking
    fun saveAsNewRecording(
        originalRecording: Recording,
        newName: String,
        newTags: List<String>,
        newTranscript: String
    ) {
        viewModelScope.launch {
            // Check for existing names to avoid overwrite
            var finalName = newName
            // We get the current list of names from the loaded state to check quickly
            val existingNames = allRecordings.value.map { File(it.recording.filePath).nameWithoutExtension }

            if (existingNames.contains(finalName)) {
                var counter = 1
                while (existingNames.contains("$newName ($counter)")) {
                    counter++
                }
                finalName = "$newName ($counter)"
            }

            repository.saveAsNewRecording(originalRecording, finalName, newTags, newTranscript)
        }
    }

    fun updateRecordingDetails(
        recording: Recording,
        newName: String,
        newTags: List<String>,
        newTranscript: String
    ) {
        viewModelScope.launch {
            repository.updateRecordingDetails(recording, newName, newTags, newTranscript)
        }
    }

    fun trimRecording(recording: Recording, startMs: Long, endMs: Long, saveAsNew: Boolean, tagToAdd: String? = null) {
        viewModelScope.launch {
            repository.trimRecording(recording, startMs, endMs, saveAsNew, tagToAdd)
        }
    }

    // Helper for the Trim Dialog to play just the segment
    // [IMPROVED] Uses PlayerManager
    fun playSegment(recording: Recording, startMs: Long, endMs: Long) {
        // Start playback at the specific position
        playerManager.startPlayback(recording.id, recording.filePath, recording.duration)
        playerManager.seekTo(startMs.toInt())

        // Monitor playback to stop at endMs
        viewModelScope.launch {
            while (isActive && playerState.value.playingRecordingId == recording.id && playerState.value.isPlaying) {
                if (playerState.value.currentPosition >= endMs) {
                    playerManager.pausePlayback()
                    playerManager.seekTo(startMs.toInt()) // Reset to start of segment
                    break
                }
                delay(50)
            }
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
        playerManager.stopPlayback()
        if (isBound) try { getApplication<Application>().unbindService(connection) } catch(e:Exception){}
    }

    // Note: PlayerState is now imported from AudioPlayerManager, but RecordingUiState is specific to this Screen
    data class RecordingUiState(
        val recording: Recording,
        val liveProgress: Float? = null,
        val liveMessage: String? = null,
        val isSemanticMatch: Boolean = false,
        val amplitudes: List<Int> = emptyList(),
        val playbackSpeed: Float = 1.0f
    )
}