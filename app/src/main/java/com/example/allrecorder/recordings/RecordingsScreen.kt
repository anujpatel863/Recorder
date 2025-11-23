package com.example.allrecorder.recordings

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.allrecorder.R
import com.example.allrecorder.Recording
import com.example.allrecorder.formatDuration
import java.io.File
import com.example.allrecorder.TranscriptExporter

@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun RecordingsScreen(
    viewModel: RecordingsViewModel
) {
    val recordings by viewModel.allRecordings.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val isRecording by remember { derivedStateOf { viewModel.isServiceRecording } }
    val context = LocalContext.current
    val searchResults by viewModel.searchResults.collectAsState()
    val currentTagFilter by viewModel.tagFilter.collectAsState()

    val recordingsToShow = searchResults ?: recordings

    DisposableEffect(Unit) {
        viewModel.bindService(context)
        onDispose { viewModel.unbindService(context) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // --- Tag Filter Indicator ---
            if (currentTagFilter != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Filtered by: #${currentTagFilter}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { viewModel.setTagFilter(null) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "Clear filter", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp, start = 8.dp, end = 8.dp)
            ) {
                if (recordingsToShow.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (searchResults != null) "No matching recordings" else "No recordings yet",
                                color = Color.Gray
                            )
                        }
                    }
                }

                items(recordingsToShow, key = { it.recording.id }) { uiState ->
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
                        // Tag Callbacks
                        onAddTag = { tag -> viewModel.addTag(uiState.recording, tag) },
                        onRemoveTag = { tag -> viewModel.removeTag(uiState.recording, tag) },
                        onTagClick = { tag -> viewModel.setTagFilter(tag) },
                        onUpdateTranscript = { txt -> viewModel.updateTranscript(uiState.recording, txt) },
                        onExport = { fmt -> viewModel.exportTranscript(context, uiState.recording, fmt) }

                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { viewModel.toggleRecordingService(context) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(if (isRecording) painterResource(R.drawable.ic_pause) else painterResource(R.drawable.ic_play_arrow), null)
            Spacer(Modifier.width(8.dp))
            Text(if (isRecording) "Stop Recording" else "Start Recording")
        }
    }
}

// --- Starred Screen ---
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun StarredRecordingsScreen(viewModel: RecordingsViewModel) {
    val recordings by viewModel.starredRecordings.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val context = LocalContext.current
    val currentTagFilter by viewModel.tagFilter.collectAsState()

    DisposableEffect(Unit) {
        viewModel.bindService(context)
        onDispose { viewModel.unbindService(context) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- Tag Filter Indicator ---
            if (currentTagFilter != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Filtered by: #${currentTagFilter}", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { viewModel.setTagFilter(null) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "Clear filter", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                if (recordings.isEmpty()) {
                    item { Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No starred recordings", color = Color.Gray) } }
                }
                items(recordings, key = { it.recording.id }) { uiState ->
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
                        onTagClick = { tag -> viewModel.setTagFilter(tag) },
                        onUpdateTranscript = { txt -> viewModel.updateTranscript(uiState.recording, txt) },
                        onExport = { fmt -> viewModel.exportTranscript(context, uiState.recording, fmt) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingItem(
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
    onSaveAs: () -> Unit,
    onExpand: () -> Unit,
    onToggleSpeed: () -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onTagClick: (String) -> Unit,
    onUpdateTranscript: (String) -> Unit,
    onExport: (TranscriptExporter.Format) -> Unit
) {
    val recording = uiState.recording
    var isExpanded by remember { mutableStateOf(false) }
    val isActive = playerState.playingRecordingId == recording.id
    val isPlayingThis = isActive && playerState.isPlaying
    var showTagDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isActive) { if (isActive) isExpanded = true }
    LaunchedEffect(isExpanded) { if (isExpanded) onExpand() }

    if (showTagDialog) {
        AddTagDialog(
            onDismiss = { showTagDialog = false },
            onConfirm = { tag ->
                onAddTag(tag)
                showTagDialog = false
            }
        )
    }
    if (showEditDialog) {
        EditTranscriptDialog(
            currentText = recording.transcript ?: "",
            onDismiss = { showEditDialog = false },
            onConfirm = { newText ->
                onUpdateTranscript(newText)
                showEditDialog = false
            }
        )
    }

    if (showExportDialog) {
        ExportFormatDialog(
            onDismiss = { showExportDialog = false },
            onConfirm = { format ->
                onExport(format)
                showExportDialog = false
            }
        )
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.clickable { isExpanded = !isExpanded }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(File(recording.filePath).name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDuration(recording.duration), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (uiState.isSemanticMatch) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.AutoAwesome, "Match", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                IconButton(onClick = onToggleStar) {
                    Icon(if (recording.isStarred) Icons.Default.Star else Icons.Default.StarBorder, "Star", tint = if (recording.isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ItemOptionsMenu(
                    onRename,
                    onDelete,
                    onSaveAs,
                    onEditTranscript = { showEditDialog = true },
                    onExportTranscript = { showExportDialog = true },
                    hasTranscript = recording.processingStatus == Recording.STATUS_COMPLETED
                )
            }

            // --- Tags Section ---
            if (recording.tags.isNotEmpty() || isExpanded) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recording.tags.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = { onTagClick(tag) },
                            label = { Text(tag) },
                            trailingIcon = if (isExpanded) {
                                {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp).clickable { onRemoveTag(tag) }
                                    )
                                }
                            } else null,
                            colors = InputChipDefaults.inputChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = null
                        )
                    }

                    if (isExpanded) {
                        SuggestionChip(
                            onClick = { showTagDialog = true },
                            label = { Text("Add Tag") },
                            icon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (uiState.liveProgress != null) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    LinearProgressIndicator(progress = { uiState.liveProgress }, modifier = Modifier.fillMaxWidth())
                    Text(uiState.liveMessage ?: "Processing...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                val statusText = when (recording.processingStatus) {
                    Recording.STATUS_COMPLETED -> recording.transcript ?: "No speech detected."
                    Recording.STATUS_PROCESSING -> "Processing..."
                    Recording.STATUS_FAILED -> "Transcription failed."
                    else -> "Unprocessed"
                }
                val textColor = if (recording.processingStatus == Recording.STATUS_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

                if (recording.processingStatus == Recording.STATUS_PROCESSING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                        overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                        color = textColor
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                PlayerControls(
                    isPlaying = isPlayingThis,
                    currentPosition = if (isActive) playerState.currentPosition else 0,
                    totalDuration = recording.duration.toInt(),
                    amplitudes = uiState.amplitudes,
                    playbackSpeed = uiState.playbackSpeed,
                    isTranscribing = recording.processingStatus == Recording.STATUS_PROCESSING,
                    onPlayPause = onPlayPause,
                    onRewind = onRewind,
                    onForward = onForward,
                    onSeek = onSeek,
                    onTranscribe = onTranscribe,
                    onToggleSpeed = onToggleSpeed
                )
            }
        }
    }
}

@Composable
fun AddTagDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Tag") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Tag Name (e.g., Work)") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConfirm(text) }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ExportFormatDialog(onDismiss: () -> Unit, onConfirm: (TranscriptExporter.Format) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Format") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onConfirm(TranscriptExporter.Format.TXT) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = false, onClick = { onConfirm(TranscriptExporter.Format.TXT) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Text File (.txt)", fontWeight = FontWeight.Bold)
                        Text("Plain text content.", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onConfirm(TranscriptExporter.Format.SRT) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = false, onClick = { onConfirm(TranscriptExporter.Format.SRT) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Subtitles (.srt)", fontWeight = FontWeight.Bold)
                        Text("For YouTube or video players. Requires valid timestamps.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
@Composable
fun EditTranscriptDialog(currentText: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(currentText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Transcript") },
        text = {
            Column {
                Text(
                    "Be careful not to change the timestamps [0.0s - 1.0s] if you want to export as SRT later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(300.dp), // Large text area
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
@Composable
private fun PlayerControls(
    isPlaying: Boolean,
    currentPosition: Int,
    totalDuration: Int,
    amplitudes: List<Int>,
    playbackSpeed: Float,
    isTranscribing: Boolean,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onTranscribe: () -> Unit,
    onToggleSpeed: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        // Waveform: Show Canvas if amplitudes exist (WAV), else Slider (M4A)
        if (amplitudes.isNotEmpty()) {
            PlaybackWaveform(
                amplitudes = amplitudes,
                progress = if (totalDuration > 0) currentPosition.toFloat() / totalDuration else 0f,
                onSeek = { percent -> onSeek(percent * totalDuration) },
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(vertical = 8.dp)
            )
        } else {
            Slider(
                value = currentPosition.toFloat(),
                onValueChange = onSeek,
                valueRange = 0f..totalDuration.toFloat()
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(currentPosition.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(totalDuration.toLong()), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToggleSpeed) {
                Text("${playbackSpeed}x", fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onRewind) { Icon(painterResource(R.drawable.ic_fast_rewind), "Rewind") }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", modifier = Modifier.fillMaxSize().padding(12.dp))
            }
            IconButton(onClick = onForward) { Icon(painterResource(R.drawable.ic_fast_forward), "Forward") }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onTranscribe,
            enabled = !isTranscribing,
            modifier = Modifier.fillMaxWidth().height(40.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
        ) {
            if (isTranscribing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Transcribing...")
            } else {
                Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Transcribe Recording")
            }
        }
    }
}

@Composable
fun PlaybackWaveform(
    amplitudes: List<Int>,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val unplayedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume(); onSeek((change.position.x / size.width).coerceIn(0f, 1f)) } }
    ) {
        val barWidth = size.width / amplitudes.size.toFloat()
        val gap = barWidth * 0.3f
        val actualBarWidth = barWidth - gap
        amplitudes.forEachIndexed { index, amplitude ->
            val barHeightPercent = (amplitude / 100f).coerceAtLeast(0.1f)
            val barHeight = barHeightPercent * size.height
            val top = (size.height - barHeight) / 2f
            val barCenterPercent = (index * barWidth + actualBarWidth / 2) / size.width
            val color = if (barCenterPercent <= progress) playedColor else unplayedColor
            drawRoundRect(color = color, topLeft = Offset(index * barWidth, top), size = Size(actualBarWidth, barHeight), cornerRadius = CornerRadius(4f, 4f))
        }
    }
}

@Composable
private fun ItemOptionsMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onSaveAs: () -> Unit,
    onEditTranscript: () -> Unit, // [NEW]
    onExportTranscript: () -> Unit, // [NEW]
    hasTranscript: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Options") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { onRename(); expanded = false }, leadingIcon = { Icon(Icons.Default.Edit, null) })
            if (hasTranscript) {
                DropdownMenuItem(
                    text = { Text("Edit Transcript") },
                    onClick = { onEditTranscript(); expanded = false },
                    leadingIcon = { Icon(Icons.Default.Description, null) }
                )
                DropdownMenuItem(
                    text = { Text("Export Transcript") },
                    onClick = { onExportTranscript(); expanded = false },
                    leadingIcon = { Icon(Icons.Default.SaveAlt, null) }
                )
            }
            DropdownMenuItem(text = { Text("Save As...") }, onClick = { onSaveAs(); expanded = false }, leadingIcon = { Icon(Icons.Default.SaveAlt, null) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { onDelete(); expanded = false }, leadingIcon = { Icon(Icons.Default.Delete, null) })
        }
    }
}