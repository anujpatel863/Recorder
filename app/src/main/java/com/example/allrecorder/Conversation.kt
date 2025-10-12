package com.example.allrecorder

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    var title: String,
    val startTime: Long, // Absolute start time (epoch milliseconds)
    val endTime: Long,   // Absolute end time (epoch milliseconds)
    val speakerCount: Int
)