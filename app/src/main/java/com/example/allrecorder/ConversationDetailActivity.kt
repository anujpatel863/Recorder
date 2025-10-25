package com.example.allrecorder

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.allrecorder.databinding.ActivityConversationDetailBinding
import kotlinx.coroutines.launch

class ConversationDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConversationDetailBinding
    private lateinit var recordingDao: RecordingDao
    private var conversationId: Long = -1

    private var mediaPlayer: MediaPlayer? = null
    private var recording: Recording? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var progressUpdater: Runnable

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recordingDao = AppDatabase.getDatabase(this).recordingDao()
        conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)

        if (conversationId != -1L) {
            loadConversationDetails()
            setupPlayerControls()
        } else {
            binding.transcriptTextView.text = "Error: Conversation not found."
        }
    }

    private fun loadConversationDetails() {
        lifecycleScope.launch {
            recording = recordingDao.getRecordingByConversationId(conversationId)
            if (recording != null) {
                supportActionBar?.title = "Conversation Details"
                binding.transcriptTextView.text = recording!!.transcript ?: "No transcript available."
                binding.totalDurationTextView.text = formatDuration(recording!!.duration)
                binding.seekBar.max = recording!!.duration.toInt()
            } else {
                supportActionBar?.title = "Error"
                binding.transcriptTextView.text = "Could not load conversation details."
            }
        }
    }

    private fun setupPlayerControls() {
        binding.playPauseButton.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                pausePlayback()
            } else {
                startPlayback()
            }
        }

        binding.rewindButton.setOnClickListener {
            mediaPlayer?.let { it.seekTo(it.currentPosition - 5000) }
        }

        binding.forwardButton.setOnClickListener {
            mediaPlayer?.let { it.seekTo(it.currentPosition + 5000) }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun startPlayback() {
        if (recording == null) return

        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(recording!!.filePath)
                    prepare()
                    setOnCompletionListener {
                        stopPlayback(resetIcon = true)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    return
                }
            }
        }
        mediaPlayer?.start()
        binding.playPauseButton.setImageResource(R.drawable.ic_pause)
        startUpdatingProgress()
    }

    private fun pausePlayback() {
        mediaPlayer?.pause()
        binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
        stopUpdatingProgress()
    }

    private fun stopPlayback(resetIcon: Boolean = false) {
        mediaPlayer?.release()
        mediaPlayer = null
        stopUpdatingProgress()
        binding.seekBar.progress = 0
        binding.currentTimeTextView.text = formatDuration(0)
        if (resetIcon) {
            binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
        }
    }

    private fun startUpdatingProgress() {
        progressUpdater = Runnable {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    binding.seekBar.progress = player.currentPosition
                    binding.currentTimeTextView.text = formatDuration(player.currentPosition.toLong())
                    handler.postDelayed(progressUpdater, 250)
                }
            }
        }
        handler.post(progressUpdater)
    }

    private fun stopUpdatingProgress() {
        if (::progressUpdater.isInitialized) {
            handler.removeCallbacks(progressUpdater)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
    }
}