package com.example.allrecorder.ui.conversations

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.example.allrecorder.AppDatabase
import com.example.allrecorder.Conversation
import com.example.allrecorder.ConversationDao

class ConversationsViewModel(application: Application) : AndroidViewModel(application) {
    private val conversationDao: ConversationDao = AppDatabase.getDatabase(application).conversationDao()

    val allConversations: LiveData<List<Conversation>> = conversationDao.getAllConversations()
}