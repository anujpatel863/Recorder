package com.example.allrecorder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
// import androidx.compose.runtime.livedata.observeAsState // Not needed for StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.allrecorder.models.ModelRegistry
import com.example.allrecorder.recordings.RecordingsScreen
import com.example.allrecorder.ui.components.BundleUiState
import com.example.allrecorder.ui.components.ModelManagementDialog
import com.example.allrecorder.ui.components.ModelManagementViewModel
import com.example.allrecorder.ui.theme.AllRecorderTheme
import com.example.allrecorder.ui.theme.Monospace
import com.example.allrecorder.ui.theme.RetroPrimary
import com.example.allrecorder.ui.theme.RetroPrimaryDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val recordAudioGranted = permissions.getOrDefault(Manifest.permission.RECORD_AUDIO, false)
            if (!recordAudioGranted) {
                Toast.makeText(this, "Audio recording permission is required.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        SettingsManager.init(this)

        if (!hasPermissions()) {
            requestPermissions()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        setContent {
            AllRecorderTheme {
                MainAppScreen()
            }
        }
    }

    @Composable
    private fun MainAppScreen() {
        val view = LocalView.current
        val window = (view.context as Activity).window
        val windowInsetsController = WindowCompat.getInsetsController(window, view)

        LaunchedEffect(Unit) {
            windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                SettingsDrawerContent()
            }
        ) {
            MainContent(onOpenDrawer = { scope.launch { drawerState.open() } })
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainContent(onOpenDrawer: () -> Unit) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AllRecorder", fontFamily = Monospace) },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Open Menu")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = RetroPrimaryDark,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        ) { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues)) {
                RecordingsScreen()
            }
        }
    }

    @Composable
    private fun SettingsDrawerContent() {
        var showChunkDialog by remember { mutableStateOf(false) }
        var showLanguageDialog by remember { mutableStateOf(false) }
        var showAsrModelDialog by remember { mutableStateOf(false) }
        var showManageDialog by remember { mutableStateOf(false) }

        // State for the switch
        var asrEnhancementEnabled by remember {
            mutableStateOf(SettingsManager.asrEnhancementEnabled)
        }

        val context = LocalContext.current
        // Shared ViewModel for managing download states
        val modelViewModel: ModelManagementViewModel = viewModel()

        ModalDrawerSheet(windowInsets = WindowInsets.systemBars) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(12.dp))

                // --- 1. Storage Management Card ---
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("AI Models Storage", style = MaterialTheme.typography.titleSmall)
                            Text("Manage downloaded models", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showManageDialog = true }) {
                            Text("Manage")
                        }
                    }
                }
                HorizontalDivider()

                Text("Recording", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                NavigationDrawerItem(
                    label = { Text("Recording Chunk Duration") },
                    selected = false,
                    onClick = { showChunkDialog = true }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Transcription (ASR)", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)

                NavigationDrawerItem(
                    label = { Text("Transcription Language") },
                    selected = false,
                    onClick = { showLanguageDialog = true }
                )

                // --- 2. Custom ASR Model Selector ---
                NavigationDrawerItem(
                    label = { Text("ASR Model") },
                    selected = false,
                    onClick = { showAsrModelDialog = true }
                )

                // --- 3. Robust Noise Reduction Switch ---
                val noiseBundle = ModelRegistry.getBundle("bundle_enhancement")!!

                // FIX 1: Use collectAsState() for StateFlow
                val noiseState by modelViewModel.getBundleState(noiseBundle).collectAsState()

                NavigationDrawerItem(
                    label = { Text("Noise Reduction") },
                    selected = false,
                    badge = {
                        if (noiseState.isDownloading) {
                            // Show spinner if downloading
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Switch(
                                // Checked ONLY if enabled settings is true AND model is physically ready
                                checked = asrEnhancementEnabled && noiseState.isReady,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) {
                                        if (noiseState.isReady) {
                                            // Ready to enable
                                            asrEnhancementEnabled = true
                                            SettingsManager.prefs.edit { putBoolean("asr_enhancement", true) }
                                        } else {
                                            // Needs download
                                            Toast.makeText(context, "Downloading Noise Reduction model...", Toast.LENGTH_SHORT).show()
                                            modelViewModel.downloadBundle(noiseBundle)
                                            // Don't enable switch yet, wait for download to finish
                                        }
                                    } else {
                                        asrEnhancementEnabled = false
                                        SettingsManager.prefs.edit { putBoolean("asr_enhancement", false) }
                                    }
                                }
                            )
                        }
                    },
                    onClick = { }
                )
            }
        }

        // --- Dialogs ---
        if (showChunkDialog) {
            ListPreferenceDialog(
                key = "chunk_duration",
                title = "Recording Chunk Duration",
                entriesResId = R.array.chunk_duration_entries,
                entryValuesResId = R.array.chunk_duration_values,
                onDismiss = { showChunkDialog = false }
            )
        }
        if (showLanguageDialog) {
            ListPreferenceDialog(
                key = "asr_language",
                title = "Transcription Language",
                entriesResId = R.array.asr_language_entries,
                entryValuesResId = R.array.asr_language_values,
                onDismiss = { showLanguageDialog = false }
            )
        }

        // --- Robust Custom Dialogs ---
        if (showAsrModelDialog) {
            AsrModelSelectionDialog(
                onDismiss = { showAsrModelDialog = false },
                viewModel = modelViewModel
            )
        }

        if (showManageDialog) {
            ModelManagementDialog(
                onDismiss = { showManageDialog = false },
                viewModel = modelViewModel
            )
        }
    }

    @Composable
    fun AsrModelSelectionDialog(
        onDismiss: () -> Unit,
        viewModel: ModelManagementViewModel
    ) {
        val context = LocalContext.current
        val currentModel = SettingsManager.asrModel

        // Maps key to (Label, BundleID)
        val options = listOf(
            Triple("tiny", "Tiny (Fast)", "bundle_asr_tiny"),
            Triple("base", "Base (Balanced)", "bundle_asr_base"),
            Triple("small", "Small (Accurate)", "bundle_asr_small"),
            Triple("medium", "Medium (Best)", "bundle_asr_medium")
        )

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Select ASR Model") },
            text = {
                Column {
                    options.forEach { (key, label, bundleId) ->
                        val bundle = ModelRegistry.getBundle(bundleId)
                        if (bundle != null) {
                            // FIX 2: Use collectAsState() for StateFlow
                            val state by viewModel.getBundleState(bundle).collectAsState()

                            // Logic: Can select only if fully ready and NOT currently downloading
                            val isSelectable = state.isReady && !state.isDownloading
                            val isSelected = (currentModel == key)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    enabled = isSelectable,
                                    onClick = {
                                        SettingsManager.prefs.edit { putString("asr_model", key) }
                                        onDismiss()
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelectable) Color.Unspecified else Color.Gray
                                    )

                                    if (state.isDownloading) {
                                        Text(
                                            "Downloading... ${(state.progress * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = RetroPrimary
                                        )
                                    } else if (!state.isReady) {
                                        Text(
                                            "Not downloaded",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                // Action Buttons
                                if (state.isDownloading) {
                                    // Show mini-loader
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else if (!state.isReady) {
                                    // Download button
                                    IconButton(onClick = {
                                        viewModel.downloadBundle(bundle)
                                        Toast.makeText(context, "Starting download...", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.CloudDownload, contentDescription = "Download")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        )
    }

    @Composable
    private fun ListPreferenceDialog(
        key: String,
        title: String,
        entriesResId: Int,
        entryValuesResId: Int,
        onDismiss: () -> Unit
    ) {
        val entries = resources.getStringArray(entriesResId)
        val entryValues = resources.getStringArray(entryValuesResId)

        val defaultManagedValue = when(key) {
            "chunk_duration" -> SettingsManager.chunkDurationMillis.toString()
            "asr_language" -> SettingsManager.asrLanguage
            "asr_model" -> SettingsManager.asrModel
            else -> entryValues.firstOrNull() ?: ""
        }

        val currentValue = SettingsManager.prefs.getString(key, defaultManagedValue)
        val (selected, setSelected) = remember { mutableStateOf(currentValue) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    entries.forEachIndexed { index, entry ->
                        if (index < entryValues.size) {
                            val value = entryValues[index]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (selected == value),
                                    onClick = { setSelected(value) }
                                )
                                Text(text = entry, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        SettingsManager.prefs.edit { putString(key, selected) }
                        onDismiss()
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )
    }
}