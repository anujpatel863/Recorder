package com.example.allrecorder

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.allrecorder.databinding.FragmentRecordingsBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class RecordingsFragment : Fragment() {

    private var _binding: FragmentRecordingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var recordingDao: RecordingDao
    private lateinit var recordingAdapter: RecordingAdapter

    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlaying: Recording? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recordingDao = AppDatabase.getDatabase(requireContext()).recordingDao()
        setupRecyclerView()
        observeRecordings()
    }

    private fun setupRecyclerView() {
        recordingAdapter = RecordingAdapter(
            onPlayClicked = { recording -> playRecording(recording) },
            onPauseClicked = { pauseRecording() },
            onSeekBarChanged = { newPosition -> mediaPlayer?.seekTo(newPosition) },
            onEditClicked = { recording -> showRenameDialog(recording) },
            onDeleteClicked = { recording -> deleteRecording(recording) }
        )
        binding.rvRecordings.apply {
            adapter = recordingAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun observeRecordings() {
        lifecycleScope.launch {
            recordingDao.getAllRecordings().collect { recordings ->
                recordingAdapter.submitList(recordings)
            }
        }
    }

    private val updateSeekBar = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    val currentPosition = it.currentPosition
                    recordingAdapter.setPlaybackState(currentlyPlaying?.id, currentPosition, isPlaying = true)
                    handler.postDelayed(this, 100)
                }
            }
        }
    }

    private fun playRecording(recording: Recording) {
        // Case 1: Resume playback on the currently paused track
        if (mediaPlayer != null && !mediaPlayer!!.isPlaying && currentlyPlaying?.id == recording.id) {
            mediaPlayer?.start()
            handler.post(updateSeekBar)
            recordingAdapter.setPlaybackState(recording.id, mediaPlayer!!.currentPosition, isPlaying = true)
            return
        }

        // Case 2: A new track is selected, so stop the old one first
        stopPlayback()

        currentlyPlaying = recording
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(recording.filePath)
                prepareAsync()
                setOnPreparedListener { mp ->
                    mp.start()
                    handler.post(updateSeekBar)
                    recordingAdapter.setPlaybackState(recording.id, 0, isPlaying = true)
                }
                setOnCompletionListener {
                    stopPlayback()
                }
            } catch (e: IOException) {
                Toast.makeText(requireContext(), "Could not play file", Toast.LENGTH_SHORT).show()
                stopPlayback()
            }
        }
    }

    private fun pauseRecording() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                handler.removeCallbacks(updateSeekBar)
                recordingAdapter.setPlaybackState(currentlyPlaying?.id, it.currentPosition, isPlaying = false)
            }
        }
    }

    private fun stopPlayback() {
        val previouslyPlayingId = currentlyPlaying?.id
        mediaPlayer?.release()
        mediaPlayer = null
        handler.removeCallbacks(updateSeekBar)
        currentlyPlaying = null
        if(previouslyPlayingId != null) {
            recordingAdapter.setPlaybackState(previouslyPlayingId, 0, isPlaying = false)
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
                val newName = editText.text.toString()
                if (newName.isNotBlank()) {
                    renameRecording(recording, "$newName.aac")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameRecording(recording: Recording, newFileName: String) {
        val oldFile = File(recording.filePath)
        val newFile = File(oldFile.parent, newFileName)

        if (newFile.exists()) {
            Toast.makeText(requireContext(), "A file with this name already exists", Toast.LENGTH_SHORT).show()
            return
        }

        if (oldFile.renameTo(newFile)) {
            val updatedRecording = recording.copy(filePath = newFile.absolutePath)
            lifecycleScope.launch {
                recordingDao.update(updatedRecording)
            }
            Toast.makeText(requireContext(), "Renamed successfully", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Rename failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRecording(recording: Recording) {
        if (recording.id == currentlyPlaying?.id) {
            stopPlayback()
        }

        lifecycleScope.launch {
            recordingDao.delete(recording)
            try {
                File(recording.filePath).delete()
            } catch (e: Exception) {
                // Log error if needed
            }
            Toast.makeText(requireContext(), "Recording deleted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

