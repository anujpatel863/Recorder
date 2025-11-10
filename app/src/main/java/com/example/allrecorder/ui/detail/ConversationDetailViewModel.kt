package com.example.allrecorder.ui.detail

import android.app.Application
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.allrecorder.AppDatabase
import com.example.allrecorder.ConversationDao
import com.example.allrecorder.FinalTranscriptSegment
import com.example.allrecorder.Recording
import com.example.allrecorder.RecordingDao
import com.example.allrecorder.TranscriptionOrchestrator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest // ADDED

enum class TranscriptionStatus { IDLE, IN_PROGRESS, DONE, ERROR }

class ConversationDetailViewModel(application: Application, private val conversationId: Long) : AndroidViewModel(application) {

    private val recordingDao: RecordingDao = AppDatabase.getDatabase(application).recordingDao()
    private val conversationDao: ConversationDao = AppDatabase.getDatabase(application).conversationDao()
    private val gson = Gson()

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList()) // MODIFIED
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow() // MODIFIED

    private var mediaPlayer: MediaPlayer? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _transcriptionStatus = MutableStateFlow(TranscriptionStatus.IDLE)
    val transcriptionStatus: StateFlow<TranscriptionStatus> = _transcriptionStatus.asStateFlow()

    private val _transcript = MutableStateFlow<List<FinalTranscriptSegment>>(emptyList())
    val transcript: StateFlow<List<FinalTranscriptSegment>> = _transcript.asStateFlow()

    private var progressUpdateJob: Job? = null
    private var transcriptionOrchestrator: TranscriptionOrchestrator? = null

    data class PlayerState(
        val isPlaying: Boolean = false,
        val currentPosition: Int = 0,
        val maxDuration: Int = 0
    )

    init {
        loadRecordingAndTranscript()
    }

    private fun loadRecordingAndTranscript() {
        viewModelScope.launch(Dispatchers.IO) {
            // Observe recordings for this conversation
            recordingDao.getRecordingsForConversation(conversationId).collectLatest { newRecordings -> // MODIFIED
                _recordings.value = newRecordings // MODIFIED
                val totalDuration = newRecordings.sumOf { it.duration }.toInt() // MODIFIED
                _playerState.update { it.copy(maxDuration = totalDuration) } // MODIFIED
            }

            // Load conversation and check for existing transcript
            val conversation = conversationDao.getConversationById(conversationId)
            conversation?.diarizedTranscript?.let { jsonTranscript ->
                if (jsonTranscript.isNotBlank()) {
                    try {
                        val type = object : TypeToken<List<FinalTranscriptSegment>>() {}.type
                        val segments: List<FinalTranscriptSegment> = gson.fromJson(jsonTranscript, type)
                        _transcript.value = segments
                        _transcriptionStatus.value = TranscriptionStatus.DONE
                        Log.i("ViewModel", "Loaded existing transcript with ${segments.size} segments.")
                    } catch (e: Exception) {
                        Log.e("ViewModel", "Failed to parse existing transcript JSON", e)
                    }
                }
            }
        }
    }

    fun runDiarizationAndTranscription(language: String) {
        val firstRecordingFilePath = _recordings.value.firstOrNull()?.filePath ?: run { // MODIFIED
            Log.e("ViewModel", "No recordings found for transcription.")
            _transcriptionStatus.value = TranscriptionStatus.ERROR
            return
        }
        if (_transcriptionStatus.value == TranscriptionStatus.IN_PROGRESS) return

        viewModelScope.launch {
            _transcriptionStatus.value = TranscriptionStatus.IN_PROGRESS
            _transcript.value = emptyList()
            try {
                transcriptionOrchestrator = TranscriptionOrchestrator(getApplication())
                val result = transcriptionOrchestrator!!.transcribe(firstRecordingFilePath, language, modelName = "tiny") // MODIFIED
                _transcript.value = result
                saveTranscriptToDatabase(result)
                _transcriptionStatus.value = TranscriptionStatus.DONE
            } catch (e: Exception) {
                Log.e("ViewModel", "Transcription failed", e)
                _transcriptionStatus.value = TranscriptionStatus.ERROR
            } finally {
                transcriptionOrchestrator?.close()
                transcriptionOrchestrator = null
            }
        }
    }

    private fun saveTranscriptToDatabase(segments: List<FinalTranscriptSegment>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonTranscript = gson.toJson(segments)
                conversationDao.updateDiarizedTranscript(conversationId, jsonTranscript)
                Log.i("ViewModel", "Successfully saved transcript to database.")
            } catch (e: Exception) {
                Log.e("ViewModel", "Failed to save transcript to database", e)
            }
        }
    }


    fun onPlayPauseClicked() {
        if (_playerState.value.isPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        val firstRecording = _recordings.value.firstOrNull() ?: run { // MODIFIED
            Log.w("ViewModel", "No recording available to play.")
            return
        }

        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(firstRecording.filePath) // MODIFIED
                    prepare()
                    setOnCompletionListener {
                        stopPlayback(resetIcon = true)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    stopPlayback()
                    return
                }
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

    fun stopPlayback(resetIcon: Boolean = false) {
        mediaPlayer?.release()
        mediaPlayer = null
        stopUpdatingProgress()
        _playerState.update {
            if(resetIcon) it.copy(isPlaying = false, currentPosition = 0)
            else it.copy(isPlaying = false)
        }
    }

    fun onSeek(progress: Float) {
        mediaPlayer?.seekTo(progress.toInt())
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

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        transcriptionOrchestrator?.close()
    }
}