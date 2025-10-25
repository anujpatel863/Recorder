package com.example.allrecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class ConversationAdapter(private val onClick: (Conversation) -> Unit) :
    ListAdapter<Conversation, ConversationAdapter.ConversationViewHolder>(ConversationDiffCallback) {

    class ConversationViewHolder(itemView: View, val onClick: (Conversation) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.tvConversationTitle)
        private val speakerCountTextView: TextView = itemView.findViewById(R.id.tvSpeakerCount)
        private var currentConversation: Conversation? = null

        init {
            itemView.setOnClickListener {
                currentConversation?.let { onClick(it) }
            }
        }

        fun bind(conversation: Conversation) {
            currentConversation = conversation
            titleTextView.text = conversation.title
            speakerCountTextView.text = "${conversation.speakerCount} speakers"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ConversationViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

object ConversationDiffCallback : DiffUtil.ItemCallback<Conversation>() {
    override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
        return oldItem == newItem
    }
}