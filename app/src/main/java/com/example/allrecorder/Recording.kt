package com.example.allrecorder

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "recordings"
)
data class Recording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    var filePath: String,
    val startTime: Long,
    val duration: Long,
    var processingStatus: Int = 0,
    var transcript: String? = null,
    var speakerLabels: String? = null ,
    var embedding: List<Float>? = null,
    var isStarred: Boolean = false,
    val tags: List<String> = emptyList()
) {
    // This makes the code easier to read
    companion object {
        const val STATUS_NOT_STARTED = 0
        const val STATUS_PROCESSING = 1
        const val STATUS_COMPLETED = 2
        const val STATUS_FAILED = -1
    }
}