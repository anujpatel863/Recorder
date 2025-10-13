package com.example.allrecorder

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.allrecorder.databinding.FragmentRecordingsBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import android.util.Log


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
            onEditClicked = { recording -> showEditDialog(recording) },
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
        if (mediaPlayer != null && !mediaPlayer!!.isPlaying && currentlyPlaying?.id == recording.id) {
            mediaPlayer?.start()
            handler.post(updateSeekBar)
            recordingAdapter.setPlaybackState(recording.id, mediaPlayer!!.currentPosition, isPlaying = true)
            return
        }

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

    // --- START OF MODIFICATION ---
    /**
     * Shows a dialog to edit the recording's name and its 'isProcessed' status.
     */
    private fun showEditDialog(recording: Recording) {
        val context = requireContext()
        // Create a container for our dialog views
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        // Input field for the file name
        val nameInput = EditText(context).apply {
            setText(File(recording.filePath).nameWithoutExtension)
        }
        layout.addView(nameInput)

        // Checkbox to toggle the isProcessed state
        val processedCheckBox = CheckBox(context).apply {
            text = "Mark as processed"
            isChecked = recording.isProcessed
            val margin = (16 * resources.displayMetrics.density).toInt()
            (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = margin
        }
        layout.addView(processedCheckBox)

        AlertDialog.Builder(context)
            .setTitle("Edit Recording")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                val newIsProcessed = processedCheckBox.isChecked
                updateRecording(recording, newName, newIsProcessed)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateRecording(originalRecording: Recording, newName: String, newIsProcessed: Boolean) {
        if (newName.isBlank()) {
            Toast.makeText(requireContext(), "File name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val oldFile = File(originalRecording.filePath)
        val fileExtension = oldFile.extension
        val newFile = File(oldFile.parent, "$newName.$fileExtension")

        var finalPath = originalRecording.filePath
        var renameSuccess = true

        // Only rename if the name has actually changed
        if (oldFile.absolutePath != newFile.absolutePath) {
            if (newFile.exists()) {
                Toast.makeText(requireContext(), "A file with this name already exists", Toast.LENGTH_SHORT).show()
                return
            }
            if (oldFile.renameTo(newFile)) {
                finalPath = newFile.absolutePath
            } else {
                renameSuccess = false
                Toast.makeText(requireContext(), "Rename failed", Toast.LENGTH_SHORT).show()
            }
        }

        if (renameSuccess) {
            // Update the recording object in the database with the new path and isProcessed state
            val updatedRecording = originalRecording.copy(filePath = finalPath, isProcessed = newIsProcessed)
            lifecycleScope.launch {
                recordingDao.update(updatedRecording)
            }
            Toast.makeText(requireContext(), "Changes saved", Toast.LENGTH_SHORT).show()
        }
    }
    // --- END OF MODIFICATION ---

    private fun deleteRecording(recording: Recording) {
        if (recording.id == currentlyPlaying?.id) {
            stopPlayback()
        }

        lifecycleScope.launch {
            recordingDao.delete(recording)
            try {
                File(recording.filePath).delete()
            } catch (e: Exception) {
                Log.e("RecordingsFragment", "Failed to delete file", e)
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