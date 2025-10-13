package com.example.allrecorder

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.allrecorder.databinding.ItemConversationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ConversationAdapter(
    private val onItemClicked: (Conversation) -> Unit
) : ListAdapter<Conversation, ConversationAdapter.ConversationViewHolder>(ConversationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        // Pass the binding and the click listener to the ViewHolder
        return ConversationViewHolder(binding, onItemClicked)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        // Get the current conversation and bind it
        val conversation = getItem(position)
        holder.bind(conversation)
    }

    /**
     * The ViewHolder now correctly receives the click listener through its constructor
     * and uses it to handle item clicks.
     */
    class ConversationViewHolder(
        private val binding: ItemConversationBinding,
        private val onItemClicked: (Conversation) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(conversation: Conversation) {
            // Set the conversation title
            binding.tvConversationTitle.text = conversation.title

            // Format the start and end times
            val startTime = formatTime(conversation.startTime)
            val endTime = formatTime(conversation.endTime)
            binding.tvConversationTimeRange.text = "$startTime - $endTime"

            // --- FIX for Speaker Count ---
            // Use the plurals resource for correct grammar ("1 Speaker" vs "2 Speakers")
            val speakerCount = conversation.speakerCount
            binding.tvSpeakerCount.text = itemView.context.resources.getQuantityString(
                R.plurals.speaker_count,
                speakerCount,
                speakerCount
            )

            // Set the click listener on the whole item view
            itemView.setOnClickListener {
                onItemClicked(conversation)
            }
        }

        private fun formatTime(timestamp: Long): String {
            // Using a format that is clear and concise
            return SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

/**
 * DiffUtil helps the adapter efficiently update the list when data changes.
 */
class ConversationDiffCallback : DiffUtil.ItemCallback<Conversation>() {
    override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
        return oldItem == newItem
    }
}