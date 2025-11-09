package com.example.allrecorder.ui.recordings

import android.text.TextUtils
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
import androidx.compose.material.icons.filled.Refresh
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
    val recordings by viewModel.allRecordings.observeAsState(emptyList())
    val playerState by viewModel.playerState.collectAsState()
    val isRecording by remember { derivedStateOf { viewModel.isServiceRecording } }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(recordings) { recording ->
                RecordingItem(
                    recording = recording,
                    playerState = playerState,
                    onPlayPause = { viewModel.onPlayPauseClicked(recording) },
                    onRewind = viewModel::onRewind,
                    onForward = viewModel::onForward,
                    onSeek = viewModel::onSeek,
                    onRename = { viewModel.renameRecording(context, recording) },
                    onDelete = { viewModel.deleteRecording(context, recording) },
                    onTranscribe = { viewModel.transcribeRecording(context, recording) }
                )
            }
            // Add padding for the button
            item { Spacer(modifier = Modifier.height(80.dp)) }
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
    recording: Recording,
    playerState: RecordingsViewModel.PlayerState,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTranscribe: () -> Unit
) {
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

            Text(
                text = recording.transcript ?: (if (recording.isProcessed) "No speech detected." else "Awaiting processing..."),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isExpanded) {
                PlayerControls(
                    isPlaying = isPlayingThis,
                    currentPosition = if(isExpandedThis) playerState.currentPosition else 0,
                    totalDuration = recording.duration.toInt(),
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
            Button(onClick = onTranscribe, shape = MaterialTheme.shapes.small) {
                Text("Transcribe", fontFamily = Monospace)
            }
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