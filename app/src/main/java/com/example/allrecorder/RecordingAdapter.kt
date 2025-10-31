package com.example.allrecorder

import android.text.TextUtils // <-- ADD THIS IMPORT
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.TimeUnit
import android.widget.Button

// Helper function to format milliseconds into MM:SS format
fun formatDuration(millis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return String.format("%02d:%02d", minutes, seconds)
}

class RecordingAdapter(
    private val listener: AdapterListener
) : ListAdapter<Recording, RecordingAdapter.RecordingViewHolder>(RecordingDiffCallback) {

    // Interface to communicate events from the adapter to the Fragment
    interface AdapterListener {
        fun onItemClicked(position: Int)
        fun onPlayPauseClicked(recording: Recording)
        fun onRewindClicked()
        fun onForwardClicked()
        fun onSeekBarChanged(progress: Int, fromUser: Boolean)
        fun onRenameClicked(recording: Recording)
        fun onDeleteClicked(recording: Recording)
        fun onTranscribeClicked(recording: Recording)
    }

    var expandedPosition = -1
    var isPlaying = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return RecordingViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RecordingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Main Views
        private val fileNameTextView: TextView = itemView.findViewById(R.id.fileNameTextView)
        private val durationTextView: TextView = itemView.findViewById(R.id.durationTextView)
        private val optionsMenuButton: ImageButton = itemView.findViewById(R.id.optionsMenuButton)
        private val transcriptTextView: TextView = itemView.findViewById(R.id.transcriptTextView)
        private val transcribeButton: Button = itemView.findViewById(R.id.transcribeButton)

        // Player Views
        private val playerControlsLayout: LinearLayout = itemView.findViewById(R.id.playerControlsLayout)
        val currentTimeTextView: TextView = itemView.findViewById(R.id.currentTimeTextView)
        val totalDurationTextView: TextView = itemView.findViewById(R.id.totalDurationTextView)
        val seekBar: SeekBar = itemView.findViewById(R.id.seekBar)
        private val playPauseButton: ImageButton = itemView.findViewById(R.id.playPauseButton)
        private val rewindButton: ImageButton = itemView.findViewById(R.id.rewindButton)
        private val forwardButton: ImageButton = itemView.findViewById(R.id.forwardButton)

        init {
            itemView.setOnClickListener {
                // Use the modern and safer 'bindingAdapterPosition'
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    listener.onItemClicked(position)
                }
            }
        }

        fun bind(recording: Recording) {
            // Use the modern and safer 'bindingAdapterPosition'
            val position = bindingAdapterPosition
            val isExpanded = position == expandedPosition

            // Toggle visibility of the player
            playerControlsLayout.visibility = if (isExpanded) View.VISIBLE else View.GONE

            // Bind common data
            fileNameTextView.text = File(recording.filePath).name
            durationTextView.text = formatDuration(recording.duration)
            transcriptTextView.text = if (recording.isProcessed) {
                recording.transcript ?: "No speech detected."
            } else {
                "Awaiting processing..."
            }

            // --- START OF MODIFICATION ---
            // Toggle maxLines for transcription based on expansion
            if (isExpanded) {
                transcriptTextView.maxLines = Integer.MAX_VALUE
                transcriptTextView.ellipsize = null
            } else {
                transcriptTextView.maxLines = 3
                transcriptTextView.ellipsize = TextUtils.TruncateAt.END
            }
            // --- END OF MODIFICATION ---

            // Bind data and set listeners only for the expanded view
            if (isExpanded) {
                playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
                totalDurationTextView.text = formatDuration(recording.duration)
                seekBar.max = recording.duration.toInt()

                playPauseButton.setOnClickListener { listener.onPlayPauseClicked(recording) }
                rewindButton.setOnClickListener { listener.onRewindClicked() }
                forwardButton.setOnClickListener { listener.onForwardClicked() }

                transcribeButton.setOnClickListener {
                    listener.onTranscribeClicked(recording)
                    // Show loading state on button
                    it.isEnabled = false
                    (it as Button).text = "..."
                }
                transcribeButton.isEnabled = true
                transcribeButton.text = "Transcribe"
                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        listener.onSeekBarChanged(progress, fromUser)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }

            optionsMenuButton.setOnClickListener { view ->
                showPopupMenu(view, recording)
            }
        }

        private fun showPopupMenu(view: View, recording: Recording) {
            val popup = PopupMenu(view.context, view)
            popup.inflate(R.menu.recording_options_menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename -> {
                        listener.onRenameClicked(recording)
                        true
                    }
                    R.id.action_delete -> {
                        listener.onDeleteClicked(recording)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }
}

object RecordingDiffCallback : DiffUtil.ItemCallback<Recording>() {
    override fun areItemsTheSame(oldItem: Recording, newItem: Recording): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Recording, newItem: Recording): Boolean {
        return oldItem == newItem
    }
}