package com.example.allrecorder.recordings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.allrecorder.R
import com.example.allrecorder.Recording
import com.example.allrecorder.formatDuration
import com.example.allrecorder.ui.theme.Monospace
import java.io.File

@Composable
fun RecordingsScreen(
    viewModel: RecordingsViewModel
) {
    val recordings by viewModel.allRecordings.observeAsState(emptyList())
    val playerState by viewModel.playerState.collectAsState()
    val isRecording by remember { derivedStateOf { viewModel.isServiceRecording } }
    val context = LocalContext.current
    val searchResults by viewModel.searchResults.collectAsState()

    // If searchResults is null, use all recordings
    val recordingsToShow = searchResults ?: recordings

    DisposableEffect(Unit) {
        viewModel.bindService(context)
        onDispose {
            viewModel.unbindService(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp, start = 8.dp, end = 8.dp)
        ) {
            if (recordingsToShow.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if(searchResults != null) "No matching recordings" else "No recordings yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )
                    }
                }
            }

            items(recordingsToShow, key = { it.recording.id }) { uiState: RecordingsViewModel.RecordingUiState ->
                RecordingItem(
                    uiState = uiState,
                    playerState = playerState,
                    onPlayPause = { viewModel.onPlayPauseClicked(uiState.recording) },
                    onRewind = viewModel::onRewind,
                    onForward = viewModel::onForward,
                    onSeek = viewModel::onSeek,
                    onRename = { viewModel.renameRecording(context, uiState.recording) },
                    onDelete = { viewModel.deleteRecording(context, uiState.recording) },
                    onTranscribe = { viewModel.transcribeRecording(context, uiState.recording) },
                    onToggleStar = { viewModel.toggleStar(uiState.recording) },
                    onSaveAs = { viewModel.saveRecordingAs(context, uiState.recording) }
                )
            }
        }

        // FAB at the bottom
        ExtendedFloatingActionButton(
            onClick = { viewModel.toggleRecordingService(context) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                if (isRecording) painterResource(R.drawable.ic_pause) else painterResource(R.drawable.ic_play_arrow),
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(if (isRecording) "Stop Recording" else "Start Recording")
        }
    }
}

// --- NEW COMPOSABLE FOR STARRED PAGE ---
@Composable
fun StarredRecordingsScreen(
    viewModel: RecordingsViewModel
) {
    // Observe only starred recordings
    val recordings by viewModel.starredRecordings.observeAsState(emptyList())
    val playerState by viewModel.playerState.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        viewModel.bindService(context)
        onDispose { viewModel.unbindService(context) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp)
        ) {
            if (recordings.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No starred recordings", color = Color.Gray)
                    }
                }
            }

            items(recordings, key = { it.recording.id }) { uiState ->
                RecordingItem(
                    uiState = uiState,
                    playerState = playerState,
                    onPlayPause = { viewModel.onPlayPauseClicked(uiState.recording) },
                    onRewind = viewModel::onRewind,
                    onForward = viewModel::onForward,
                    onSeek = viewModel::onSeek,
                    onRename = { viewModel.renameRecording(context, uiState.recording) },
                    onDelete = { viewModel.deleteRecording(context, uiState.recording) },
                    onTranscribe = { viewModel.transcribeRecording(context, uiState.recording) },
                    onToggleStar = { viewModel.toggleStar(uiState.recording) },
                    onSaveAs = { viewModel.saveRecordingAs(context, uiState.recording) }
                )
            }
        }
    }
}

@Composable
private fun RecordingItem(
    uiState: RecordingsViewModel.RecordingUiState,
    playerState: RecordingsViewModel.PlayerState,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTranscribe: () -> Unit,
    onToggleStar: () -> Unit,
    onSaveAs: () -> Unit
) {
    val recording = uiState.recording
    var isExpanded by remember { mutableStateOf(false) }
    val isPlayingThis = playerState.playingRecordingId == recording.id && playerState.isPlaying
    val isExpandedThis = playerState.playingRecordingId == recording.id

    LaunchedEffect(isExpandedThis) {
        if (isExpandedThis) isExpanded = true
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        )
    ) {
        Column(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = File(recording.filePath).name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatDuration(recording.duration),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (uiState.isSemanticMatch) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Semantic Match",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Star Icon Action
                IconButton(onClick = onToggleStar) {
                    Icon(
                        imageVector = if (recording.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (recording.isStarred) "Unstar" else "Star",
                        tint = if (recording.isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ItemOptionsMenu(onRename, onDelete, onSaveAs)
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.liveProgress != null) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    LinearProgressIndicator(
                        progress = { uiState.liveProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = uiState.liveMessage ?: "Processing...",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                when (recording.processingStatus) {
                    Recording.STATUS_COMPLETED -> {
                        Text(
                            text = recording.transcript ?: "No speech detected.",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                            overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Recording.STATUS_PROCESSING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(
                                "Processing transcription...",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Recording.STATUS_FAILED -> {
                        Text(
                            text = recording.transcript ?: "Transcription failed.",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                            overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Recording.STATUS_NOT_STARTED -> {
                        Text(
                            text = "unprocessed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                PlayerControls(
                    isPlaying = isPlayingThis,
                    currentPosition = if (isExpandedThis) playerState.currentPosition else 0,
                    totalDuration = recording.duration.toInt(),
                    isTranscribing = recording.processingStatus == Recording.STATUS_PROCESSING,
                    onPlayPause = onPlayPause,
                    onRewind = onRewind,
                    onForward = onForward,
                    onSeek = onSeek,
                    onTranscribe = onTranscribe
                )
            }
        }
    }
}

@Composable
private fun PlayerControls(
    isPlaying: Boolean,
    currentPosition: Int,
    totalDuration: Int,
    isTranscribing: Boolean,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onTranscribe: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(formatDuration(currentPosition.toLong()), style = MaterialTheme.typography.labelSmall)
            Slider(
                value = currentPosition.toFloat(),
                onValueChange = onSeek,
                valueRange = 0f..totalDuration.toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Text(formatDuration(totalDuration.toLong()), style = MaterialTheme.typography.labelSmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onRewind) {
                Icon(painterResource(R.drawable.ic_fast_rewind), contentDescription = "Rewind 5s")
            }
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.fillMaxSize().padding(12.dp)
                )
            }
            IconButton(onClick = onForward) {
                Icon(painterResource(R.drawable.ic_fast_forward), contentDescription = "Forward 5s")
            }

            Button(
                onClick = onTranscribe,
                shape = MaterialTheme.shapes.small,
                enabled = !isTranscribing,
                modifier = Modifier.height(40.dp)
            ) {
                if (isTranscribing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Transcribe", fontFamily = Monospace)
                }
            }
        }
    }
}

@Composable
private fun ItemOptionsMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onSaveAs: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Options")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    onRename()
                    expanded = false
                },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = "Rename") }
            )
            DropdownMenuItem(
                text = { Text("Save As...") },
                onClick = {
                    onSaveAs()
                    expanded = false
                },
                leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = "Save As") }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    onDelete()
                    expanded = false
                },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            )
        }
    }
}