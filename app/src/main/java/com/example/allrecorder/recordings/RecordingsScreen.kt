
package com.example.allrecorder.recordings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
    val selectedIds by viewModel.selectedIds.collectAsState()

    // Re-indexing UI State
    val showReindexDialog = viewModel.showReindexDialog
    val isReindexing = viewModel.isReindexing
    val reindexProgress = viewModel.reindexProgress

    val recordingsToShow = searchResults ?: recordings
    val isSelectionMode = selectedIds.isNotEmpty()

    // BackHandler to clear selection
    BackHandler(enabled = isSelectionMode) {
        viewModel.clearSelection()
    }

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
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.toggleRecordingService(context) },
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(if (isRecording) painterResource(R.drawable.ic_pause) else painterResource(R.drawable.ic_play_arrow), null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRecording) recordingDuration else "Start Recording")
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // --- Tag Filter Indicator ---
                if (currentTagFilter != null && !isSelectionMode) {
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
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedIds.contains(uiState.recording.id),
                            onToggleSelection = { viewModel.toggleSelection(uiState.recording.id) },
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
                                viewModel.duplicateRecording( uiState.recording, "duplicate")
                            },
                            onTrimConfirm = { start, end, copy ->
                                // Add "trimmed" tag automatically
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
                            onTagClick = { tag -> viewModel.setTagFilter(tag) },
                            onRemoveTag = { tag -> viewModel.removeTag(uiState.recording, tag) },
                            onExport = { fmt -> viewModel.exportTranscript(context, uiState.recording, fmt) }
                        )
                    }
                }
            }
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
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isSelectionMode = selectedIds.isNotEmpty()

    BackHandler(enabled = isSelectionMode) { viewModel.clearSelection() }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    onClearSelection = { viewModel.clearSelection() },
                    onSelectAll = { viewModel.selectAll() }, // Note: This might select ALL recordings, not just starred. To fix, VM needs "selectAllStarred" or selectAll uses visible list. Current VM uses visible list.
                    onDeleteSelected = { viewModel.deleteSelected(context) },
                    onTranscribeSelected = { viewModel.transcribeSelected(context) },
                    onDownloadSelected = { viewModel.downloadSelected(context) }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (currentTagFilter != null && !isSelectionMode) {
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
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedIds.contains(uiState.recording.id),
                            onToggleSelection = { viewModel.toggleSelection(uiState.recording.id) },
                            onPlayPause = { viewModel.onPlayPauseClicked(uiState.recording) },
                            onRewind = viewModel::onRewind,
                            onForward = viewModel::onForward,
                            onSeek = { p -> viewModel.onSeek(uiState.recording, p) },

                            onUpdateDetails = { name, tags, transcript -> viewModel.updateRecordingDetails(uiState.recording, name, tags, transcript) },
                            onSaveAsNew = { name, tags, transcript -> viewModel.saveAsNewRecording(uiState.recording, name, tags + "saveAs", transcript) },
                            onDuplicate = { viewModel.duplicateRecording( uiState.recording, "duplicate") },
                            onTrimConfirm = { start, end, copy -> viewModel.trimRecording(uiState.recording, start, end, copy, "trimmed") },
                            onPreviewTrim = { start, end -> viewModel.playSegment(uiState.recording, start, end) },
                            onStopPreview = { viewModel.stopPlayback() },

                            onDelete = { viewModel.deleteRecording(context, uiState.recording) },
                            onTranscribe = { viewModel.transcribeRecording( uiState.recording) },
                            onToggleStar = { viewModel.toggleStar(uiState.recording) },
                            onSaveToDownloads = { viewModel.saveRecordingAs( uiState.recording) },
                            onExpand = { viewModel.loadAmplitudes(uiState.recording) },
                            onToggleSpeed = { viewModel.togglePlaybackSpeed() },
                            onTagClick = { tag -> viewModel.setTagFilter(tag) },
                            onRemoveTag = { tag -> viewModel.removeTag(uiState.recording, tag) },
                            onExport = { fmt -> viewModel.exportTranscript(context, uiState.recording, fmt) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordingItem(
    uiState: RecordingsViewModel.RecordingUiState,
    playerState: AudioPlayerManager.PlayerState,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
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

    LaunchedEffect(isActive) { if (isActive && !isSelectionMode) isExpanded = true }
    LaunchedEffect(isExpanded) { if (isExpanded) onExpand() }

    // Close expanded state if we enter selection mode
    LaunchedEffect(isSelectionMode) { if (isSelectionMode) isExpanded = false }

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

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        label = "selectionColor"
    )

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .combinedClickable(
                    onClick = {
                        if (isSelectionMode) {
                            onToggleSelection()
                        } else {
                            isExpanded = !isExpanded
                        }
                    },
                    onLongClick = {
                        if (!isSelectionMode) {
                            onToggleSelection()
                        }
                    }
                )
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Checkbox for selection mode
                AnimatedVisibility(visible = isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(File(recording.filePath).name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDuration(recording.duration), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (!isSelectionMode) {
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
            }

            if (recording.tags.isNotEmpty() || (isExpanded && !isSelectionMode)) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recording.tags.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = { if (!isSelectionMode) onTagClick(tag) },
                            label = { Text(tag) },
                            trailingIcon = if (isExpanded && !isSelectionMode) { { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp).clickable { onRemoveTag(tag) }) } } else null,
                            colors = InputChipDefaults.inputChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), labelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                            border = null
                        )
                    }
                    if (isExpanded && recording.tags.isEmpty() && !isSelectionMode) {
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

            AnimatedVisibility(visible = isExpanded && !isSelectionMode) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onTranscribeSelected: () -> Unit,
    onDownloadSelected: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("$selectedCount Selected") },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Default.Close, contentDescription = "Close selection")
            }
        },
        actions = {
            // Select All Button
            TextButton(onClick = onSelectAll) {
                Text("Select All", fontWeight = FontWeight.Bold)
            }

            // Delete Icon
            IconButton(onClick = onDeleteSelected) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Selected")
            }

            // More Menu (3 dots)
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More Options")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { onDeleteSelected(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Transcribe") },
                        onClick = { onTranscribeSelected(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Description, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Download") },
                        onClick = { onDownloadSelected(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.SaveAlt, null) }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

// ... (Rest of existing dialogs: EditDetailsDialog, TrimRecordingDialog, ExportFormatDialog, PlayerControls, ItemOptionsMenu, alpha helper - unchanged)
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
    val simplePlayback = SettingsManager.simplePlaybackEnabled

    // [LOGIC] 0.0 - 1.0 Calculation
    val currentProgress = if (totalDuration > 0) currentPosition.toFloat() / totalDuration else 0f

    // [INTERFACE FIX] Added horizontal padding (24.dp) to move controls away from the edge.
    // This prevents the Navigation Drawer from intercepting your scrub gesture.
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {

        if (simplePlayback) {
            Slider(
                value = currentProgress,
                onValueChange = onSeek,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        } else {
            if (amplitudes.isNotEmpty()) {
                // [STYLE] Container for waveform with a subtle background
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp) // Taller for better touch target
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.medium
                        )
                        .padding(horizontal = 12.dp, vertical = 16.dp), // Inner padding
                    contentAlignment = Alignment.Center
                ) {
                    PlaybackWaveform(
                        amplitudes = amplitudes,
                        progress = currentProgress,
                        onSeek = onSeek,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }

        // Time Labels
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(currentPosition.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = formatDuration(totalDuration.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speed Button (Styled)
            TextButton(
                onClick = onToggleSpeed,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
            ) {
                Text("${playbackSpeed}x", fontWeight = FontWeight.Bold)
            }

            IconButton(onClick = onRewind) {
                Icon(painterResource(R.drawable.ic_fast_rewind), "Rewind", tint = MaterialTheme.colorScheme.onSurface)
            }

            // Play/Pause (Prominent)
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(64.dp), // Slightly larger
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/Pause",
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            IconButton(onClick = onForward) {
                Icon(painterResource(R.drawable.ic_fast_forward), "Forward", tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Transcribe Button
        OutlinedButton(
            onClick = onTranscribe,
            enabled = !isTranscribing,
            modifier = Modifier.fillMaxWidth().height(40.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        ) {
            if (isTranscribing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Processing...", style = MaterialTheme.typography.bodySmall)
            } else {
                Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Transcribe", style = MaterialTheme.typography.bodyMedium)
            }
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
