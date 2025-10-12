package com.example.allrecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.allrecorder.databinding.ItemRecordingBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RecordingAdapter(
    private val onPlayClicked: (Recording) -> Unit,
    private val onPauseClicked: () -> Unit,
    private val onSeekBarChanged: (Int) -> Unit,
    private val onEditClicked: (Recording) -> Unit,
    private val onDeleteClicked: (Recording) -> Unit
) : ListAdapter<Recording, RecordingAdapter.RecordingViewHolder>(RecordingDiffCallback()) {

    var currentlyPlayingId: Long? = null
    var currentProgress: Int = 0

    fun setPlaybackState(playingId: Long?, progress: Int) {
        val previousPlayingId = currentlyPlayingId
        currentlyPlayingId = playingId
        currentProgress = progress

        // Notify previous item to update its UI (e.g., hide pause button)
        if (previousPlayingId != null) {
            val oldPosition = currentList.indexOfFirst { it.id == previousPlayingId }
            if (oldPosition != -1) notifyItemChanged(oldPosition)
        }
        // Notify new item to update its UI
        if (playingId != null) {
            val newPosition = currentList.indexOfFirst { it.id == playingId }
            if (newPosition != -1) notifyItemChanged(newPosition, progress)
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val binding = ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RecordingViewHolder(binding, onSeekBarChanged)
    }

    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        val recording = getItem(position)
        val isPlaying = recording.id == currentlyPlayingId
        holder.bind(recording, isPlaying, currentProgress, onPlayClicked, onPauseClicked, onEditClicked, onDeleteClicked)
    }

    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val progress = payloads[0] as Int
            holder.updateProgress(progress)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }


    class RecordingViewHolder(
        private val binding: ItemRecordingBinding,
        private val onSeekBarChanged: (Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            recording: Recording,
            isPlaying: Boolean,
            progress: Int,
            onPlayClicked: (Recording) -> Unit,
            onPauseClicked: () -> Unit,
            onEditClicked: (Recording) -> Unit,
            onDeleteClicked: (Recording) -> Unit
        ) {
            binding.tvFileName.text = File(recording.filePath).name
            binding.tvTimestamp.text = formatDate(recording.startTime)
            binding.tvDuration.text = formatDuration(recording.duration)
            binding.seekBar.max = recording.duration.toInt()
            binding.seekBar.progress = if (isPlaying) progress else 0

            if (isPlaying) {
                binding.btnPlay.visibility = View.GONE
                binding.btnPause.visibility = View.VISIBLE
            } else {
                binding.btnPlay.visibility = View.VISIBLE
                binding.btnPause.visibility = View.GONE
            }

            binding.btnPlay.setOnClickListener { onPlayClicked(recording) }
            binding.btnPause.setOnClickListener { onPauseClicked() }
            binding.btnEdit.setOnClickListener { onEditClicked(recording) }
            binding.btnDelete.setOnClickListener { onDeleteClicked(recording) }
            binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        onSeekBarChanged(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        fun updateProgress(progress: Int) {
            binding.seekBar.progress = progress
        }

        private fun formatDate(timestamp: Long): String {
            val date = Date(timestamp)
            val format = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.getDefault())
            return format.format(date)
        }

        private fun formatDuration(millis: Long): String {
            val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
            val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
            return String.format("%02d:%02d", minutes, seconds)
        }
    }
}

class RecordingDiffCallback : DiffUtil.ItemCallback<Recording>() {
    override fun areItemsTheSame(oldItem: Recording, newItem: Recording): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Recording, newItem: Recording): Boolean {
        return oldItem == newItem
    }
}
