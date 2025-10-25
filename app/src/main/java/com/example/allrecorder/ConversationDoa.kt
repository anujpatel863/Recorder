package com.example.allrecorder

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Query("SELECT * FROM conversations ORDER BY startTime DESC")
    fun getAllConversations(): LiveData<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun getConversationById(conversationId: Long): Flow<Conversation>
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: Long): Conversation?
}
