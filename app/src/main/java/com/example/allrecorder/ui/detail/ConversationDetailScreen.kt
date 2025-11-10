package com.example.allrecorder.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.allrecorder.FinalTranscriptSegment
import com.example.allrecorder.R
import com.example.allrecorder.Recording
import com.example.allrecorder.formatDuration
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailScreen(
    recordings: List<Recording>, // MODIFIED
    playerState: ConversationDetailViewModel.PlayerState,
    transcriptionStatus: TranscriptionStatus,
    transcript: List<FinalTranscriptSegment>,
    onNavigateUp: () -> Unit,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onTranscribe: (String) -> Unit
) {
    var showLanguageMenu by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf("en") } // Default to English
    val languages = listOf("en", "hi", "gu", "mr") // Example languages

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recordings.firstOrNull()?.let { File(it.filePath).nameWithoutExtension } ?: "Conversation") }, // MODIFIED
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (transcriptionStatus != TranscriptionStatus.IN_PROGRESS) {
                        Button(onClick = { onTranscribe(selectedLanguage) }) {
                            Text("Transcribe")
                        }
                    }
                    IconButton(onClick = { showLanguageMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Select Language")
                    }
                    DropdownMenu(
                        expanded = showLanguageMenu,
                        onDismissRequest = { showLanguageMenu = false }
                    ) {
                        languages.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang) },
                                onClick = {
                                    selectedLanguage = lang
                                    showLanguageMenu = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            if (recordings.isNotEmpty()) { // MODIFIED
                PlayerControls(
                    playerState = playerState,
                    onPlayPause = onPlayPause,
                    onRewind = onRewind,
                    onForward = onForward,
                    onSeek = onSeek
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when (transcriptionStatus) {
                TranscriptionStatus.IN_PROGRESS -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                TranscriptionStatus.DONE -> {
                    if (transcript.isNotEmpty()) {
                        TranscriptView(transcript = transcript)
                    } else {
                        Text("Transcription complete, but no text was generated.", modifier = Modifier.align(Alignment.Center))
                    }
                }
                TranscriptionStatus.ERROR -> {
                    Text("An error occurred during transcription.", modifier = Modifier.align(Alignment.Center))
                }
                TranscriptionStatus.IDLE -> {
                    Text(
                        "Press 'Transcribe' to start.",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptView(transcript: List<FinalTranscriptSegment>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(transcript) { segment ->
            Card {
                Row(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Speaker ${segment.speakerId}:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.width(100.dp)
                    )
                    Text(
                        text = segment.text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}


@Composable
private fun PlayerControls(
    playerState: ConversationDetailViewModel.PlayerState,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Float) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatDuration(playerState.currentPosition.toLong()), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = playerState.currentPosition.toFloat(),
                    onValueChange = onSeek,
                    valueRange = 0f..playerState.maxDuration.toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(formatDuration(playerState.maxDuration.toLong()), style = MaterialTheme.typography.labelSmall)
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
                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                IconButton(onClick = onForward) {
                    Icon(painterResource(R.drawable.ic_fast_forward), contentDescription = "Forward 5s")
                }
            }
        }
    }
}