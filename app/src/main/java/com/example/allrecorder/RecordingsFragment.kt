package com.example.allrecorder

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.allrecorder.databinding.FragmentRecordingsBinding
import kotlinx.coroutines.launch
import java.io.File
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlin.math.log

class RecordingsFragment : Fragment() {

    private var _binding: FragmentRecordingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var recordingDao: RecordingDao
    private lateinit var asrService: AsrService

    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingRecording: Recording? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var progressUpdater: Runnable

    private val recordingAdapter: RecordingAdapter by lazy {
        RecordingAdapter(object : RecordingAdapter.AdapterListener {
            override fun onItemClicked(position: Int) {
                handleItemClick(position)
            }

            override fun onPlayPauseClicked(recording: Recording) {
                if (mediaPlayer?.isPlaying == true) {
                    pausePlayback()
                } else {
                    startPlayback(recording)
                }
            }

            override fun onRewindClicked() {
                mediaPlayer?.let { it.seekTo(it.currentPosition - 5000) }
            }

            override fun onForwardClicked() {
                mediaPlayer?.let { it.seekTo(it.currentPosition + 5000) }
            }

            override fun onSeekBarChanged(progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                }
            }

            override fun onRenameClicked(recording: Recording) {
                showRenameDialog(recording)
            }

            override fun onDeleteClicked(recording: Recording) {
                showDeleteConfirmationDialog(recording)
            }
            override fun onTranscribeClicked(recording: Recording) {
                transcribeRecording(recording)
            }
        })
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingsBinding.inflate(inflater, container, false)
        SettingsManager.init(requireContext())
        asrService = AsrService(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recordingDao = AppDatabase.getDatabase(requireContext()).recordingDao()

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = recordingAdapter
        }

        recordingDao.getAllRecordings().observe(viewLifecycleOwner) { recordings ->
            recordingAdapter.submitList(recordings)
        }

        binding.recordButton.setOnClickListener {
            toggleRecordingService()
        }
    }

    private fun handleItemClick(position: Int) {
        val previouslyExpandedPosition = recordingAdapter.expandedPosition

        if (previouslyExpandedPosition == position) {
            // Collapse the item
            recordingAdapter.expandedPosition = -1
            stopPlayback()
        } else {
            // Expand the new item
            recordingAdapter.expandedPosition = position
            stopPlayback() // Stop any playback before switching
        }

        // Notify adapter about the changes
        if (previouslyExpandedPosition != -1) {
            recordingAdapter.notifyItemChanged(previouslyExpandedPosition)
        }
        recordingAdapter.notifyItemChanged(position)
    }

    private fun startPlayback(recording: Recording) {
        // If it's a new recording, create a new MediaPlayer instance
        if (currentPlayingRecording?.id != recording.id) {
            stopPlayback() // Release any existing player
            currentPlayingRecording = recording
            mediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(recording.filePath)
                    prepare()
                    setOnCompletionListener {
                        stopPlayback()
                        // Refresh the item to show it's no longer playing
                        if (recordingAdapter.expandedPosition != -1) {
                            recordingAdapter.notifyItemChanged(recordingAdapter.expandedPosition)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Clean up on error
                    stopPlayback()
                    return
                }
            }
        }

        mediaPlayer?.start()
        recordingAdapter.isPlaying = true
        if (recordingAdapter.expandedPosition != -1) {
            recordingAdapter.notifyItemChanged(recordingAdapter.expandedPosition)
        }
        startUpdatingProgress()
    }

    private fun pausePlayback() {
        mediaPlayer?.pause()
        recordingAdapter.isPlaying = false
        if (recordingAdapter.expandedPosition != -1) {
            recordingAdapter.notifyItemChanged(recordingAdapter.expandedPosition)
        }
        stopUpdatingProgress()
    }

    private fun stopPlayback() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentPlayingRecording = null
        recordingAdapter.isPlaying = false
        stopUpdatingProgress()
    }

    private fun startUpdatingProgress() {
        progressUpdater = Runnable {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    val vh = binding.recyclerView.findViewHolderForAdapterPosition(recordingAdapter.expandedPosition)
                    if (vh is RecordingAdapter.RecordingViewHolder) {
                        vh.seekBar.progress = player.currentPosition
                        vh.currentTimeTextView.text = formatDuration(player.currentPosition.toLong())
                    }
                    handler.postDelayed(progressUpdater, 250) // Update every 250ms
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

    private fun showRenameDialog(recording: Recording) {
        val editText = EditText(requireContext()).apply {
            setText(File(recording.filePath).nameWithoutExtension)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Rename Recording")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
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

    private fun showDeleteConfirmationDialog(recording: Recording) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Recording")
            .setMessage("Are you sure you want to permanently delete this recording?")
            .setPositiveButton("Delete") { _, _ ->
                if (recording.id == currentPlayingRecording?.id) {
                    stopPlayback()
                    recordingAdapter.expandedPosition = -1 // Collapse item
                }
                lifecycleScope.launch {
                    File(recording.filePath).delete()
                    recordingDao.delete(recording)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleRecordingService() {
        val intent = Intent(requireActivity(), RecordingService::class.java)
        if (RecordingService.isRecording) {
            requireActivity().stopService(intent)
            binding.recordButton.text = "Start Recording"
        } else {
            requireActivity().startService(intent)
            binding.recordButton.text = "Stop Recording"
        }
    }
    private fun transcribeRecording(recording: Recording) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val language = SettingsManager.asrLanguage
            val decoder = SettingsManager.asrDecoder

            // Run transcription
            val transcript = try {
                if (decoder == "rnnt") {
                    asrService.transcribeRnnt(recording.filePath, language)
                } else {
                    asrService.transcribeCtc(recording.filePath, language)
                }

            } catch (e: Exception) {
                Log.e("RecordingsFragment", "Transcription failed", e)
                "[Transcription Failed: ${e.message}]"
            }
            Log.d(tag,"decoder completed")
            Log.d(tag,transcript)
            // Save to database
            recording.transcript = transcript
            recording.isProcessed = true // Mark as processed
            recordingDao.update(recording)

            // UI will update automatically via LiveData
        }
    }

    override fun onResume() {
        super.onResume()
        binding.recordButton.text = if (RecordingService.isRecording) "Stop Recording" else "Start Recording"
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
        if (recordingAdapter.expandedPosition != -1) {
            val oldPos = recordingAdapter.expandedPosition
            recordingAdapter.expandedPosition = -1
            recordingAdapter.notifyItemChanged(oldPos)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        asrService.close()
        _binding = null
    }
}