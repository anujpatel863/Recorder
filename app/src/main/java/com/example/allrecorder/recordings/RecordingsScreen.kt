package com.example.allrecorder.recordings

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.allrecorder.R
import com.example.allrecorder.Recording
import com.example.allrecorder.formatDuration
import com.example.allrecorder.TranscriptExporter
import com.example.allrecorder.SettingsManager
import java.io.File

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
    val recordingDuration by viewModel.formattedDuration.collectAsState()

    // Re-indexing UI State
    val showReindexDialog = viewModel.showReindexDialog
    val isReindexing = viewModel.isReindexing
    val reindexProgress = viewModel.reindexProgress

    val recordingsToShow = searchResults ?: recordings

    DisposableEffect(Unit) {
        viewModel.bindService(context)
        onDispose { viewModel.unbindService(context) }
    }

    // --- Dialogs ---
    if (showReindexDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissReindexDialog() },
            title = { Text("Update Search Index") },
            text = { Text("Some recordings are missing semantic search data. Generate embeddings now to enable smart search for all items?") },
            confirmButton = { Button(onClick = { viewModel.startReindexing() }) { Text("Update") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissReindexDialog() }) { Text("Later") } }
        )
    }

    if (isReindexing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Indexing...") },
            text = {
                Column {
                    Text("Generating embeddings for older recordings...")
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { reindexProgress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(text = "${(reindexProgress * 100).toInt()}%", modifier = Modifier.align(Alignment.End), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {}
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- Tag Filter Indicator ---
            if (currentTagFilter != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(text = "Filtered by: #${currentTagFilter}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
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
                    item { Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(if (searchResults != null) "No matching recordings" else "No recordings yet", color = Color.Gray) } }
                }

                items(recordingsToShow, key = { it.recording.id }) { uiState ->
                    RecordingItem(
                        uiState = uiState,
                        playerState = playerState,
                        onPlayPause = { viewModel.onPlayPauseClicked(uiState.recording) },
                        onRewind = viewModel::onRewind,
                        onForward = viewModel::onForward,
                        onSeek = { p -> viewModel.onSeek(uiState.recording, p) },

                        // Actions with Auto-Tagging
                        onUpdateDetails = { name, tags, transcript ->
                            viewModel.updateRecordingDetails(uiState.recording, name, tags, transcript)
                        },
                        onSaveAsNew = { name, tags, transcript ->
                            // Add "saveAs" tag automatically
                            viewModel.saveAsNewRecording(uiState.recording, name, tags + "saveAs", transcript)
                        },
                        onDuplicate = {
                            // Add "duplicate" tag automatically
                            viewModel.duplicateRecording(context, uiState.recording, "duplicate")
                        },
                        onTrimConfirm = { start, end, copy ->
                            // Add "trimmed" tag automatically
                            viewModel.trimRecording(uiState.recording, start, end, copy, "trimmed")
                        },
                        onPreviewTrim = { start, end -> viewModel.playSegment(uiState.recording, start, end) },
                        onStopPreview = { viewModel.stopPlayback() },

                        onDelete = { viewModel.deleteRecording(context, uiState.recording) },
                        onTranscribe = { viewModel.transcribeRecording(context, uiState.recording) },
                        onToggleStar = { viewModel.toggleStar(uiState.recording) },
                        onSaveToDownloads = { viewModel.saveRecordingAs(context, uiState.recording) },
                        onExpand = { viewModel.loadAmplitudes(uiState.recording) },
                        onToggleSpeed = { viewModel.togglePlaybackSpeed(uiState.recording) },
                        onTagClick = { tag -> viewModel.setTagFilter(tag) },
                        onRemoveTag = { tag -> viewModel.removeTag(uiState.recording, tag) },
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
            Text(if (isRecording) recordingDuration else "Start Recording")
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (currentTagFilter != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Filtered by: #${currentTagFilter}", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { viewModel.setTagFilter(null) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, "Clear filter", modifier = Modifier.size(16.dp)) }
                    }
                }
            }

            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                if (recordings.isEmpty()) { item { Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No starred recordings", color = Color.Gray) } } }
                items(recordings, key = { it.recording.id }) { uiState ->
                    RecordingItem(
                        uiState = uiState,
                        playerState = playerState,
                        onPlayPause = { viewModel.onPlayPauseClicked(uiState.recording) },
                        onRewind = viewModel::onRewind,
                        onForward = viewModel::onForward,
                        onSeek = { p -> viewModel.onSeek(uiState.recording, p) },

                        onUpdateDetails = { name, tags, transcript -> viewModel.updateRecordingDetails(uiState.recording, name, tags, transcript) },
                        onSaveAsNew = { name, tags, transcript -> viewModel.saveAsNewRecording(uiState.recording, name, tags + "saveAs", transcript) },
                        onDuplicate = { viewModel.duplicateRecording(context, uiState.recording, "duplicate") },
                        onTrimConfirm = { start, end, copy -> viewModel.trimRecording(uiState.recording, start, end, copy, "trimmed") },
                        onPreviewTrim = { start, end -> viewModel.playSegment(uiState.recording, start, end) },
                        onStopPreview = { viewModel.stopPlayback() },

                        onDelete = { viewModel.deleteRecording(context, uiState.recording) },
                        onTranscribe = { viewModel.transcribeRecording(context, uiState.recording) },
                        onToggleStar = { viewModel.toggleStar(uiState.recording) },
                        onSaveToDownloads = { viewModel.saveRecordingAs(context, uiState.recording) },
                        onExpand = { viewModel.loadAmplitudes(uiState.recording) },
                        onToggleSpeed = { viewModel.togglePlaybackSpeed(uiState.recording) },
                        onTagClick = { tag -> viewModel.setTagFilter(tag) },
                        onRemoveTag = { tag -> viewModel.removeTag(uiState.recording, tag) },
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
    onUpdateDetails: (String, List<String>, String) -> Unit,
    onSaveAsNew: (String, List<String>, String) -> Unit,
    onDuplicate: () -> Unit,
    onTrimConfirm: (Long, Long, Boolean) -> Unit,
    onPreviewTrim: (Long, Long) -> Unit,
    onStopPreview: () -> Unit,
    onDelete: () -> Unit,
    onTranscribe: () -> Unit,
    onToggleStar: () -> Unit,
    onSaveToDownloads: () -> Unit,
    onExpand: () -> Unit,
    onToggleSpeed: () -> Unit,
    onRemoveTag: (String) -> Unit,
    onTagClick: (String) -> Unit,
    onExport: (TranscriptExporter.Format) -> Unit
) {
    val recording = uiState.recording
    var isExpanded by remember { mutableStateOf(false) }
    val isActive = playerState.playingRecordingId == recording.id
    val isPlayingThis = isActive && playerState.isPlaying

    var showEditDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showTrimDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isActive) { if (isActive) isExpanded = true }
    LaunchedEffect(isExpanded) { if (isExpanded) onExpand() }

    if (showEditDialog) {
        EditDetailsDialog(
            recording = recording,
            onDismiss = { showEditDialog = false },
            onConfirmSave = { name, tags, transcript -> onUpdateDetails(name, tags, transcript); showEditDialog = false },
            onConfirmSaveAs = { name, tags, transcript -> onSaveAsNew(name, tags, transcript); showEditDialog = false }
        )
    }

    if (showTrimDialog) {
        TrimRecordingDialog(
            recording = recording,
            amplitudes = uiState.amplitudes,
            onDismiss = { showTrimDialog = false },
            onConfirm = { start, end, copy -> onTrimConfirm(start, end, copy); showTrimDialog = false },
            onPreview = onPreviewTrim,
            onStopPreview = onStopPreview
        )
    }

    if (showExportDialog) {
        ExportFormatDialog(onDismiss = { showExportDialog = false }, onConfirm = { format -> onExport(format); showExportDialog = false })
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
                    }
                }
                IconButton(onClick = onToggleStar) {
                    Icon(if (recording.isStarred) Icons.Default.Star else Icons.Default.StarBorder, "Star", tint = if (recording.isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }

                ItemOptionsMenu(
                    onEditDetails = { showEditDialog = true },
                    onDuplicate = onDuplicate,
                    onTrim = { showTrimDialog = true },
                    onDelete = onDelete,
                    onSaveToDownloads = onSaveToDownloads,
                    onExportTranscript = { showExportDialog = true },
                    hasTranscript = recording.processingStatus == Recording.STATUS_COMPLETED
                )
            }

            if (recording.tags.isNotEmpty() || isExpanded) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recording.tags.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = { onTagClick(tag) },
                            label = { Text(tag) },
                            trailingIcon = if (isExpanded) { { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp).clickable { onRemoveTag(tag) }) } } else null,
                            colors = InputChipDefaults.inputChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), labelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                            border = null
                        )
                    }
                    if (isExpanded && recording.tags.isEmpty()) {
                        SuggestionChip(onClick = { showEditDialog = true }, label = { Text("Add Tag") }, icon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) })
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
                    Text(text = statusText, style = MaterialTheme.typography.bodyMedium, maxLines = if (isExpanded) Int.MAX_VALUE else 3, overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis, color = textColor)
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

// --- Smart Edit Dialog ---
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditDetailsDialog(
    recording: Recording,
    onDismiss: () -> Unit,
    onConfirmSave: (String, List<String>, String) -> Unit,
    onConfirmSaveAs: (String, List<String>, String) -> Unit
) {
    var name by remember { mutableStateOf(File(recording.filePath).nameWithoutExtension) }
    var transcript by remember { mutableStateOf(recording.transcript ?: "") }
    var currentTags by remember { mutableStateOf(recording.tags) }
    var newTagText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        title = { Text("Edit Details") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("File Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text("Tags", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                FlowRow(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    currentTags.forEach { tag ->
                        InputChip(selected = true, onClick = { /* No-op */ }, label = { Text(tag) }, trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp).clickable { currentTags = currentTags - tag }) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newTagText, onValueChange = { newTagText = it }, label = { Text("New Tag") }, modifier = Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { if (newTagText.isNotBlank() && !currentTags.contains(newTagText.trim())) { currentTags = currentTags + newTagText.trim(); newTagText = "" } }) { Icon(Icons.Default.Add, "Add") }
                }
                Spacer(Modifier.height(16.dp))
                if (recording.processingStatus == Recording.STATUS_COMPLETED) {
                    Text("Transcript", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(value = transcript, onValueChange = { transcript = it }, modifier = Modifier.fillMaxWidth().height(150.dp), textStyle = MaterialTheme.typography.bodySmall, placeholder = { Text("Transcript text...") })
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { if (name.isNotBlank()) onConfirmSaveAs(name, currentTags, transcript) }) { Text("Save as Copy") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { if (name.isNotBlank()) onConfirmSave(name, currentTags, transcript) }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimRecordingDialog(
    recording: Recording,
    amplitudes: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Long, Long, Boolean) -> Unit,
    onPreview: (Long, Long) -> Unit,
    onStopPreview: () -> Unit
) {
    var range by remember { mutableStateOf(0f..1f) }
    var isPreviewing by remember { mutableStateOf(false) }
    val durationMs = recording.duration
    val startMs = (range.start * durationMs).toLong()
    val endMs = (range.endInclusive * durationMs).toLong()

    DisposableEffect(Unit) { onDispose { onStopPreview() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.95f).padding(16.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Trim Recording", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Drag sliders to crop the audio.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    if (amplitudes.isNotEmpty()) {
                        PlaybackWaveform(amplitudes = amplitudes, progress = -1f, onSeek = {}, modifier = Modifier.fillMaxSize().alpha(0.5f), barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    } else { Box(Modifier.fillMaxSize().background(Color.LightGray.copy(alpha=0.2f))) }
                    RangeSlider(value = range, onValueChange = { range = it; if (isPreviewing) { isPreviewing = false; onStopPreview() } }, modifier = Modifier.fillMaxWidth())
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("Start", style = MaterialTheme.typography.labelSmall); Text(formatDuration(startMs), fontWeight = FontWeight.Bold) }
                    Text("Selected: ${formatDuration(endMs - startMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    Column(horizontalAlignment = Alignment.End) { Text("End", style = MaterialTheme.typography.labelSmall); Text(formatDuration(endMs), fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = { if (isPreviewing) { onStopPreview(); isPreviewing = false } else { onPreview(startMs, endMs); isPreviewing = true } }, modifier = Modifier.align(Alignment.CenterHorizontally), colors = ButtonDefaults.filledTonalButtonColors()) {
                    Icon(if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (isPreviewing) "Stop Preview" else "Preview Section")
                }
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onConfirm(startMs, endMs, true) }) { Text("Save Copy") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(startMs, endMs, false) }) { Text("Trim Original") }
                }
            }
        }
    }
}

@Composable
fun ExportFormatDialog(onDismiss: () -> Unit, onConfirm: (TranscriptExporter.Format) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Format") },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth().clickable { onConfirm(TranscriptExporter.Format.TXT) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = false, onClick = { onConfirm(TranscriptExporter.Format.TXT) }); Spacer(Modifier.width(8.dp))
                    Column { Text("Text File (.txt)", fontWeight = FontWeight.Bold); Text("Plain text content.", style = MaterialTheme.typography.bodySmall) }
                }
                Row(modifier = Modifier.fillMaxWidth().clickable { onConfirm(TranscriptExporter.Format.SRT) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = false, onClick = { onConfirm(TranscriptExporter.Format.SRT) }); Spacer(Modifier.width(8.dp))
                    Column { Text("Subtitles (.srt)", fontWeight = FontWeight.Bold); Text("For YouTube or video players.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PlayerControls(
    isPlaying: Boolean, currentPosition: Int, totalDuration: Int, amplitudes: List<Int>, playbackSpeed: Float, isTranscribing: Boolean,
    onPlayPause: () -> Unit, onRewind: () -> Unit, onForward: () -> Unit, onSeek: (Float) -> Unit, onTranscribe: () -> Unit, onToggleSpeed: () -> Unit
) {
    val simplePlayback = SettingsManager.simplePlaybackEnabled
    Column(modifier = Modifier.padding(top = 12.dp)) {
        if (simplePlayback) {
            Slider(value = currentPosition.toFloat(), onValueChange = onSeek, valueRange = 0f..totalDuration.toFloat().coerceAtLeast(1f))
        } else {
            if (amplitudes.isNotEmpty()) {
                PlaybackWaveform(amplitudes = amplitudes, progress = if (totalDuration > 0) currentPosition.toFloat() / totalDuration else 0f, onSeek = { percent -> onSeek(percent * totalDuration) }, modifier = Modifier.fillMaxWidth().height(60.dp).padding(vertical = 8.dp))
            } else { Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) } }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(currentPosition.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(totalDuration.toLong()), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToggleSpeed) { Text("${playbackSpeed}x", fontWeight = FontWeight.Bold) }
            IconButton(onClick = onRewind) { Icon(painterResource(R.drawable.ic_fast_rewind), "Rewind") }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", modifier = Modifier.fillMaxSize().padding(12.dp)) }
            IconButton(onClick = onForward) { Icon(painterResource(R.drawable.ic_fast_forward), "Forward") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onTranscribe, enabled = !isTranscribing, modifier = Modifier.fillMaxWidth().height(40.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
            if (isTranscribing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Transcribing...") }
            else { Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Transcribe Recording") }
        }
    }
}

@Composable
private fun ItemOptionsMenu(
    onEditDetails: () -> Unit, onDuplicate: () -> Unit, onTrim: () -> Unit, onDelete: () -> Unit, onSaveToDownloads: () -> Unit, onExportTranscript: () -> Unit, hasTranscript: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Options") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Edit Details") }, onClick = { onEditDetails(); expanded = false }, leadingIcon = { Icon(Icons.Default.Edit, null) })
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { onDuplicate(); expanded = false }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
            DropdownMenuItem(text = { Text("Trim Audio") }, onClick = { onTrim(); expanded = false }, leadingIcon = { Icon(Icons.Default.ContentCut, null) })
            HorizontalDivider()
            if (hasTranscript) { DropdownMenuItem(text = { Text("Export Transcript") }, onClick = { onExportTranscript(); expanded = false }, leadingIcon = { Icon(Icons.Default.Description, null) }) }
            DropdownMenuItem(text = { Text("Save to Downloads") }, onClick = { onSaveToDownloads(); expanded = false }, leadingIcon = { Icon(Icons.Default.SaveAlt, null) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { onDelete(); expanded = false }, leadingIcon = { Icon(Icons.Default.Delete, null) })
        }
    }
}

// Helper for Alpha Modifier
fun Modifier.alpha(alpha: Float) = this.then(Modifier.drawWithContent {
    drawContent()
    drawRect(Color.Black, alpha = 1f - alpha, blendMode = BlendMode.DstIn)
})