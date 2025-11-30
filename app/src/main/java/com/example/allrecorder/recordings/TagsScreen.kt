package com.example.allrecorder.recordings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
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

    // Selection State
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isSelectionMode = selectedIds.isNotEmpty()

    // Local state to track which tag is currently selected (if any)
    var selectedTag by remember { mutableStateOf<String?>(null) }

    // Handle Back Button:
    // 1. If in selection mode, clear selection.
    // 2. If viewing a tag, go back to tag list.
    BackHandler(enabled = isSelectionMode) {
        viewModel.clearSelection()
    }
    BackHandler(enabled = !isSelectionMode && selectedTag != null) {
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
            Scaffold(
                topBar = {
                    if (isSelectionMode) {
                        SelectionTopBar(
                            selectedCount = selectedIds.size,
                            onClearSelection = { viewModel.clearSelection() },
                            onSelectAll = { viewModel.selectAll() },
                            onDeleteSelected = { viewModel.deleteSelected(context) },
                            onTranscribeSelected = { viewModel.transcribeSelected(context) },
                            onDownloadSelected = { viewModel.downloadSelected(context) }
                        )
                    } else {
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
                    }
                }
            ) { paddingValues ->
                // Filtered List
                val taggedRecordings = allRecordings.filter { it.recording.tags.contains(selectedTag) }

                LazyColumn(
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding() + 8.dp,
                        bottom = 80.dp,
                        start = 8.dp,
                        end = 8.dp
                    )
                ) {
                    items(taggedRecordings, key = { it.recording.id }) { uiState ->

                        // [UPDATED] Matches RecordingsScreen with Selection Params
                        RecordingItem(
                            uiState = uiState,
                            playerState = playerState,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedIds.contains(uiState.recording.id),
                            onToggleSelection = { viewModel.toggleSelection(uiState.recording.id) },

                            onPlayPause = { viewModel.onPlayPauseClicked(uiState.recording) },
                            onRewind = viewModel::onRewind,
                            onForward = viewModel::onForward,
                            onSeek = { p -> viewModel.onSeek(uiState.recording, p) },

                            // Smart Actions
                            onUpdateDetails = { name, tags, transcript ->
                                viewModel.updateRecordingDetails(uiState.recording, name, tags, transcript)
                            },
                            onSaveAsNew = { name, tags, transcript ->
                                viewModel.saveAsNewRecording(uiState.recording, name, tags + "saveAs", transcript)
                            },
                            onDuplicate = {
                                viewModel.duplicateRecording( uiState.recording, "duplicate")
                            },
                            onTrimConfirm = { start, end, copy ->
                                viewModel.trimRecording(uiState.recording, start, end, copy, "trimmed")
                            },
                            onPreviewTrim = { start, end -> viewModel.playSegment(uiState.recording, start, end) },
                            onStopPreview = { viewModel.stopPlayback() },

                            onDelete = { viewModel.deleteRecording(context, uiState.recording) },
                            onTranscribe = { viewModel.transcribeRecording( uiState.recording) },
                            onToggleStar = { viewModel.toggleStar(uiState.recording) },
                            onSaveToDownloads = { viewModel.saveRecordingAs( uiState.recording) },
                            onExpand = { viewModel.loadAmplitudes(uiState.recording) },
                            onToggleSpeed = { viewModel.togglePlaybackSpeed() },

                            // Tag navigation within Tag Screen just switches the view
                            onTagClick = { tag -> selectedTag = tag },
                            onRemoveTag = { tag -> viewModel.removeTag(uiState.recording, tag) },
                            onExport = { fmt -> viewModel.exportTranscript(context, uiState.recording, fmt) }
                        )
                    }
                }
            }
        }
    }
}