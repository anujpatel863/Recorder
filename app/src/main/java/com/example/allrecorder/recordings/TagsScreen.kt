package com.example.allrecorder.recordings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsScreen(viewModel: RecordingsViewModel) {
    val availableTags by viewModel.availableTags.collectAsState()
    val allRecordings by viewModel.allRecordings.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val context = LocalContext.current

    // Local state to track which tag is currently selected (if any)
    var selectedTag by remember { mutableStateOf<String?>(null) }

    // Handle Back Button: If inside a tag, go back to tag list.
    BackHandler(enabled = selectedTag != null) {
        selectedTag = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedTag == null) {
            // --- STATE 1: TAG OVERVIEW (List of all tags) ---
            if (availableTags.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tags created yet.", color = Color.Gray)
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        "Browse by Tag",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableTags.forEach { tag ->
                            // Count how many recordings have this tag
                            val count = allRecordings.count { it.recording.tags.contains(tag) }

                            ElevatedFilterChip(
                                selected = false,
                                onClick = { selectedTag = tag },
                                label = { Text("$tag ($count)") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.Label, null, modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // --- STATE 2: TAGGED RECORDINGS LIST ---
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with Back Button
                Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedTag = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                        Text(
                            "Tag: #$selectedTag",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Filtered List
                val taggedRecordings = allRecordings.filter { it.recording.tags.contains(selectedTag) }

                LazyColumn(
                    contentPadding = PaddingValues(bottom = 80.dp, top = 8.dp, start = 8.dp, end = 8.dp)
                ) {
                    items(taggedRecordings, key = { it.recording.id }) { uiState ->
                        // Reuse the exact same item from RecordingsScreen
                        // We need to duplicate the callbacks here or refactor RecordingItem to be public (it is)
                        // Note: To avoid duplication in a real app, RecordingItem should be in a shared file.
                        // Assuming RecordingItem is available in this package (it is in RecordingsScreen.kt):

                        // Copying the item usage from RecordingsScreen.kt:
                        RecordingItem(
                            uiState = uiState,
                            playerState = playerState,
                            onPlayPause = { viewModel.onPlayPauseClicked(uiState.recording) },
                            onRewind = viewModel::onRewind,
                            onForward = viewModel::onForward,
                            onSeek = { p -> viewModel.onSeek(uiState.recording, p) },
                            onRename = { viewModel.renameRecording(context, uiState.recording) },
                            onDelete = { viewModel.deleteRecording(context, uiState.recording) },
                            onTranscribe = { viewModel.transcribeRecording(context, uiState.recording) },
                            onToggleStar = { viewModel.toggleStar(uiState.recording) },
                            onSaveAs = { viewModel.saveRecordingAs(context, uiState.recording) },
                            onExpand = { viewModel.loadAmplitudes(uiState.recording) },
                            onToggleSpeed = { viewModel.togglePlaybackSpeed(uiState.recording) },
                            onAddTag = { tag -> viewModel.addTag(uiState.recording, tag) },
                            onRemoveTag = { tag -> viewModel.removeTag(uiState.recording, tag) },
                            onTagClick = { tag -> selectedTag = tag },
                            onUpdateTranscript = { txt -> viewModel.updateTranscript(uiState.recording, txt) },
                            onExport = { fmt -> viewModel.exportTranscript(context, uiState.recording, fmt) }

                        )
                    }
                }
            }
        }
    }
}