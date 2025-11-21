package com.example.allrecorder.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.allrecorder.models.ModelBundle
import com.example.allrecorder.models.ModelRegistry
import com.example.allrecorder.ui.theme.RetroPrimary

@Composable
fun ModelManagementDialog(
    onDismiss: () -> Unit,
    viewModel: ModelManagementViewModel = viewModel()
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage AI Models") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Manage offline resources.", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(ModelRegistry.bundles) { bundle ->
                        ModelBundleItem(bundle, viewModel)
                        Divider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun ModelBundleItem(
    bundle: ModelBundle,
    viewModel: ModelManagementViewModel
) {
    // Trigger a refresh check when this item appears
    LaunchedEffect(viewModel.refreshTrigger) {
        // The ViewModel's LiveData handles the logic,
        // but this ensures UI recomposition if file system changed externally
    }

    // OBSERVE THE ROBUST STATE
    val state by viewModel.getBundleState(bundle).observeAsState(BundleUiState())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = bundle.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = bundle.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Downloading... ${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        when {
            state.isDownloading -> {
                // Cancel Button
                IconButton(onClick = { viewModel.deleteOrCancelBundle(bundle) }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                }
            }
            state.isReady -> {
                // Delete Button
                IconButton(onClick = { viewModel.deleteOrCancelBundle(bundle) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                // Download Button
                IconButton(onClick = { viewModel.downloadBundle(bundle) }) {
                    Icon(Icons.Default.CloudDownload, contentDescription = "Download", tint = RetroPrimary)
                }
            }
        }
    }
}