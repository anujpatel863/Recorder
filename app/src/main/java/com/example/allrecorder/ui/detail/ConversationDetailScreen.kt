package com.example.allrecorder.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.allrecorder.R
import com.example.allrecorder.Recording
import com.example.allrecorder.formatDuration
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailScreen(
    recording: Recording?,
    playerState: ConversationDetailViewModel.PlayerState,
    onNavigateUp: () -> Unit,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Float) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recording?.let { File(it.filePath).nameWithoutExtension } ?: "Conversation") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            if (recording != null) {
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
            if (recording == null) {
                Text("Loading...", modifier = Modifier.align(Alignment.Center))
            } else {
                Text(
                    text = recording.transcript ?: "No transcript available.",
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
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