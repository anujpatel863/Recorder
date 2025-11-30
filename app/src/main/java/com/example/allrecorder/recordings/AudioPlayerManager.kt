package com.example.allrecorder.recordings

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayerManager @Inject constructor(
    @param: ApplicationContext private val context: Context
) {
    data class PlayerState(
        val playingRecordingId: Long? = null,
        val isPlaying: Boolean = false,
        val currentPosition: Int = 0,
        val maxDuration: Int = 0,
        val playbackSpeed: Float = 1.0f
    )

    private var mediaPlayer: MediaPlayer? = null
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun startPlayback(recordingId: Long, filePath: String, durationMs: Long) {
        // Stop any previous playback
        if (_playerState.value.playingRecordingId != recordingId) {
            stopPlayback()
        }

        // Resume or Start New
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(filePath)
                    prepare()

                    // Restore speed
                    val speed = _playerState.value.playbackSpeed
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speed != 1.0f) {
                        playbackParams = PlaybackParams().setSpeed(speed)
                    }

                    setOnCompletionListener {
                        stopPlayback(resetPosition = true)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    stopPlayback()
                    return
                }
            }
            // Update duration and ID
            _playerState.update { it.copy(playingRecordingId = recordingId, maxDuration = durationMs.toInt()) }
        }

        mediaPlayer?.start()
        _playerState.update { it.copy(isPlaying = true) }
        startProgressUpdates()
    }

    fun pausePlayback() {
        mediaPlayer?.pause()
        _playerState.update { it.copy(isPlaying = false) }
        stopProgressUpdates()
    }

    fun stopPlayback(resetPosition: Boolean = false) {
        stopProgressUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        _playerState.update {
            it.copy(
                isPlaying = false,
                // Keep ID and position if we want to "pause" effectively,
                // but if explicit stop, clear everything or just reset state
                playingRecordingId = if(resetPosition) null else it.playingRecordingId,
                currentPosition = if(resetPosition) 0 else it.currentPosition
            )
        }
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
        _playerState.update { it.copy(currentPosition = positionMs) } // <-- Updates UI immediately!
    }

    fun setPlaybackSpeed(speed: Float) {
        try {
            mediaPlayer?.playbackParams = mediaPlayer?.playbackParams?.setSpeed(speed) ?: PlaybackParams().setSpeed(speed)
        } catch (e: Exception) { }
        _playerState.update { it.copy(playbackSpeed = speed) }
    }

    fun toggleSpeed() {
        val current = _playerState.value.playbackSpeed
        val newSpeed = when (current) {
            1.0f -> 1.5f; 1.5f -> 2.0f; 2.0f -> 0.5f; else -> 1.0f
        }
        setPlaybackSpeed(newSpeed)
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive) {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        _playerState.update { it.copy(currentPosition = mp.currentPosition) }
                    }
                }
                delay(50) // Update rate
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }
}