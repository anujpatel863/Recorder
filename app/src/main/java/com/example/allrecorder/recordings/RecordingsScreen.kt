package com.example.allrecorder.recordings

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.allrecorder.R
import com.example.allrecorder.Recording
import com.example.allrecorder.formatDuration
import com.example.allrecorder.ui.theme.Monospace
import java.io.File

@Composable
fun RecordingsScreen(
    viewModel: RecordingsViewModel = viewModel()
) {
    // 'recordings' is now of type List<RecordingsViewModel.RecordingUiState>
    val recordings by viewModel.allRecordings.observeAsState(emptyList())
    val playerState by viewModel.playerState.collectAsState()
    val isRecording by remember { derivedStateOf { viewModel.isServiceRecording } }
    val context = LocalContext.current
    val audioData by viewModel.audioData.collectAsState()

    // Bind to service when the screen is shown
    DisposableEffect(Unit) {
        viewModel.bindService(context)
        onDispose {
            viewModel.unbindService(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column {
            if (isRecording) {
                AudioVisualizer(audioData = audioData)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp)
            ) {
                // --- 1. MODIFICATION ---
                // The item is now 'RecordingUiState'
                items(recordings, key = { it.recording.id }) { uiState: RecordingsViewModel.RecordingUiState ->
                    RecordingItem(
                        // Pass the entire uiState object
                        uiState = uiState,
                        playerState = playerState,
                        // Update callbacks to use uiState.recording
                        onPlayPause = { viewModel.onPlayPauseClicked(uiState.recording) },
                        onRewind = viewModel::onRewind,
                        onForward = viewModel::onForward,
                        onSeek = viewModel::onSeek,
                        onRename = { viewModel.renameRecording(context, uiState.recording) },
                        onDelete = { viewModel.deleteRecording(context, uiState.recording) },
                        onTranscribe = { viewModel.transcribeRecording(context, uiState.recording) }
                    )
                }
                // Add padding for the button
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }

        Button(
            onClick = { viewModel.toggleRecordingService(context) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.BottomCenter)
        ) {
            Text(if (isRecording) "Stop Recording" else "Start Recording")
        }
    }
}

@Composable
private fun RecordingItem(
    // --- 2. MODIFICATION ---
    // Parameter is now RecordingUiState
    uiState: RecordingsViewModel.RecordingUiState,
    playerState: RecordingsViewModel.PlayerState,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTranscribe: () -> Unit
) {
    // Extract the recording for easier use
    val recording = uiState.recording

    var isExpanded by remember { mutableStateOf(false) }
    val isPlayingThis = playerState.playingRecordingId == recording.id && playerState.isPlaying
    val isExpandedThis = playerState.playingRecordingId == recording.id

    // Auto-expand when playback starts
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
                    Text(
                        text = formatDuration(recording.duration),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ItemOptionsMenu(onRename, onDelete)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- 3. MODIFICATION ---
            // Check for *live* progress first.
            if (uiState.liveProgress != null) {
                // Show the LIVE progress bar
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    LinearProgressIndicator(
                        progress = { uiState.liveProgress }, // Use lambda for M3
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
                // No live progress, fall back to the static status
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
                        // This is now the "fallback" view for a processing
                        // item that isn't the *currently* active one.
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
                            text = "Awaiting processing...",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                            overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // --- END MODIFICATION ---

            if (isExpanded) {
                PlayerControls(
                    isPlaying = isPlayingThis,
                    currentPosition = if (isExpandedThis) playerState.currentPosition else 0,
                    totalDuration = recording.duration.toInt(),
                    // Pass the processing status down
                    // This button logic is now controlled by *both*
                    // the static status and the live progress.
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
    isTranscribing: Boolean, // Added this
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
            IconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.fillMaxSize()
                )
            }
            IconButton(onClick = onForward) {
                Icon(painterResource(R.drawable.ic_fast_forward), contentDescription = "Forward 5s")
            }
            // --- MODIFIED BUTTON ---
            Button(
                onClick = onTranscribe,
                shape = MaterialTheme.shapes.small,
                // Disable the button if transcription is in progress
                enabled = !isTranscribing
            ) {
                // Show a progress bar inside the button if processing
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
            // --- END MODIFICATION ---
        }
    }
}

@Composable
private fun ItemOptionsMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit
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