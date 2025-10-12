package com.example.allrecorder

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recordings",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class Recording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val filePath: String,
    val startTime: Long, // Unix timestamp in milliseconds
    val duration: Long, // Duration in milliseconds
    var conversationId: Long? = null,
    var isProcessed: Boolean = false,
    val transcript: String? = null,
    var speakerLabels: String? = null // This will be populated by the worker
)

