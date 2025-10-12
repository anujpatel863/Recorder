package com.example.allrecorder

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.allrecorder.databinding.ActivityConversationDetailBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ConversationDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConversationDetailBinding
    private lateinit var recordingDao: RecordingDao
    private var conversation: Conversation? = null

    private var mediaPlayer: MediaPlayer? = null
    private var recordings: List<Recording> = emptyList()
    private var currentTrackIndex = 0
    private var totalDuration: Long = 0
    private var currentPlaybackPosition: Int = 0

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recordingDao = AppDatabase.getDatabase(this).recordingDao()

        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)
        if (conversationId == -1L) {
            finish()
            return
        }

        lifecycleScope.launch {
            // Fetch the conversation details and associated recordings
            conversation = AppDatabase.getDatabase(this@ConversationDetailActivity)
                .conversationDao().getConversationById(conversationId).first()
            recordings = recordingDao.getRecordingsForConversation(conversationId).first()

            if (recordings.isEmpty()) {
                Toast.makeText(this@ConversationDetailActivity, "No recordings found for this conversation.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            setupUI()
            setupPlayback()
        }
    }

    private fun setupUI() {
        conversation?.let {
            title = "Conversation Details"
            binding.tvConversationTitle.text = it.title
            val startTime = formatTime(it.startTime)
            val endTime = formatTime(it.endTime)
            binding.tvConversationTimeRange.text = "$startTime - $endTime"
        }

        totalDuration = recordings.sumOf { it.duration }
        binding.seekBar.max = totalDuration.toInt()
        binding.tvTotalDuration.text = formatDuration(totalDuration)

        binding.btnPlay.setOnClickListener { playOrPause() }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    seekTo(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupPlayback() {
        currentTrackIndex = 0
        playCurrentTrack()
    }

    private fun playCurrentTrack() {
        if (currentTrackIndex >= recordings.size) {
            // Reached the end of the conversation
            stopPlayback()
            return
        }

        mediaPlayer?.release() // Release any previous instance
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(recordings[currentTrackIndex].filePath)
                prepareAsync()
                setOnPreparedListener {
                    it.start()
                    binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                    handler.post(updateSeekBarTask)
                }
                setOnCompletionListener {
                    currentPlaybackPosition += recordings[currentTrackIndex].duration.toInt()
                    currentTrackIndex++
                    playCurrentTrack()
                }
            } catch (e: IOException) {
                Toast.makeText(this@ConversationDetailActivity, "Could not play file.", Toast.LENGTH_SHORT).show()
                stopPlayback()
            }
        }
    }

    private fun playOrPause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
                handler.removeCallbacks(updateSeekBarTask)
            } else {
                it.start()
                binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                handler.post(updateSeekBarTask)
            }
        }
    }

    private val updateSeekBarTask = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    val progress = currentPlaybackPosition + it.currentPosition
                    binding.seekBar.progress = progress
                    binding.tvCurrentTime.text = formatDuration(progress.toLong())
                    handler.postDelayed(this, 100)
                }
            }
        }
    }

    private fun seekTo(position: Int) {
        var accumulatedDuration = 0
        var targetTrack = -1
        var seekInTrack = 0

        for ((index, recording) in recordings.withIndex()) {
            if (position < accumulatedDuration + recording.duration) {
                targetTrack = index
                seekInTrack = position - accumulatedDuration
                break
            }
            accumulatedDuration += recording.duration.toInt()
        }

        if (targetTrack != -1) {
            currentTrackIndex = targetTrack
            currentPlaybackPosition = accumulatedDuration
            playCurrentTrack()
            mediaPlayer?.setOnPreparedListener {
                it.seekTo(seekInTrack)
                it.start()
                binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                handler.post(updateSeekBarTask)
            }
        }
    }


    private fun stopPlayback() {
        handler.removeCallbacks(updateSeekBarTask)
        mediaPlayer?.release()
        mediaPlayer = null
        binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
        binding.seekBar.progress = 0
        binding.tvCurrentTime.text = formatDuration(0)
    }

    private fun formatDuration(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun formatTime(timestamp: Long): String {
        val date = Date(timestamp)
        val format = SimpleDateFormat("h:mm a", Locale.getDefault())
        return format.format(date)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }
}
