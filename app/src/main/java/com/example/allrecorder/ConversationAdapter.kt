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

class ConversationAdapter : ListAdapter<Conversation, ConversationAdapter.ConversationViewHolder>(ConversationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ConversationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        val conversation = getItem(position)
        holder.bind(conversation)
    }

    class ConversationViewHolder(private val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root) {
        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun bind(conversation: Conversation) {
            binding.tvConversationTitle.text = conversation.title
            val startTime = timeFormat.format(Date(conversation.startTime))
            val endTime = timeFormat.format(Date(conversation.endTime))
            binding.tvConversationTimeRange.text = "$startTime - $endTime"
        }
    }
}

class ConversationDiffCallback : DiffUtil.ItemCallback<Conversation>() {
    override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
        return oldItem == newItem
    }
}
