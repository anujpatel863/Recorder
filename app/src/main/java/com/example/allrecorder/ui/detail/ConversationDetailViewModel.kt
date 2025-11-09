package com.example.allrecorder.ui.detail

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.allrecorder.AppDatabase
import com.example.allrecorder.Recording
import com.example.allrecorder.RecordingDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ConversationDetailViewModel(application: Application, private val conversationId: Long) : AndroidViewModel(application) {

    private val recordingDao: RecordingDao = AppDatabase.getDatabase(application).recordingDao()

    private val _recording = MutableStateFlow<Recording?>(null)
    val recording: StateFlow<Recording?> = _recording.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var progressUpdateJob: Job? = null

    data class PlayerState(
        val isPlaying: Boolean = false,
        val currentPosition: Int = 0,
        val maxDuration: Int = 0
    )

    init {
        loadRecording()
    }

    private fun loadRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            val rec = recordingDao.getRecordingByConversationId(conversationId)
            _recording.value = rec
            _playerState.update { it.copy(maxDuration = rec?.duration?.toInt() ?: 0) }
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
        val currentRecording = _recording.value ?: return

        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(currentRecording.filePath)
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
    }
}