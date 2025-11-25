package com.example.allrecorder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.allrecorder.models.ModelRegistry
import com.example.allrecorder.recordings.AudioVisualizer
import com.example.allrecorder.recordings.RecordingsScreen
import com.example.allrecorder.recordings.StarredRecordingsScreen
import com.example.allrecorder.recordings.RecordingsViewModel
import com.example.allrecorder.recordings.TagsScreen
import com.example.allrecorder.ui.components.ModelManagementDialog
import com.example.allrecorder.ui.components.ModelManagementViewModel
import com.example.allrecorder.ui.theme.AllRecorderTheme
import com.example.allrecorder.ui.theme.Monospace
import com.example.allrecorder.ui.theme.RetroPrimary
import com.example.allrecorder.ui.theme.RetroPrimaryDark
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.content.Context
import android.content.Intent

enum class Screen {
    Home, Starred, Tags
}

@AndroidEntryPoint
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        SettingsManager.init(this)

        if (!hasPermissions()) {
            requestPermissions()
        }
        if (SettingsManager.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // [NEW] Logic for Auto-Record on Launch
        // We check if permission exists and if we aren't already recording
        if (SettingsManager.autoRecordOnLaunch && hasPermissions() && !RecordingService.isRecording) {
            startService(Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
            })
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
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }


    @RequiresApi(Build.VERSION_CODES.Q)
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

        // Navigation State
        var currentScreen by remember { mutableStateOf(Screen.Home) }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                SettingsDrawerContent(
                    currentScreen = currentScreen,
                    onScreenSelected = { screen ->
                        currentScreen = screen
                        scope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            MainContent(
                currentScreen = currentScreen,
                onOpenDrawer = { scope.launch { drawerState.open() } }
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainContent(
        currentScreen: Screen,
        onOpenDrawer: () -> Unit
    ) {
        // [FIX] hiltViewModel() now uses the correct import
        val recordingsViewModel: RecordingsViewModel = hiltViewModel()

        val audioData by recordingsViewModel.audioData.collectAsState()
        val isRecording by remember { derivedStateOf { recordingsViewModel.isServiceRecording } }

        // State for search mode interaction
        var isSearchActive by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }

        val titleText = when(currentScreen) {
            Screen.Home -> "AllRecorder"
            Screen.Starred -> "Starred"
            Screen.Tags -> "Browse Tags "
        }

        Scaffold(
            containerColor = RetroPrimaryDark,
            topBar = {
                Surface(
                    color = RetroPrimaryDark,
                    shadowElevation = 4.dp,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                ) {
                    if (isSearchActive) {
                        // --- Search Mode View ---
                        Row(
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                isSearchActive = false
                                searchQuery = ""
                                recordingsViewModel.performSemanticSearch("")
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onPrimary)
                            }
                            TextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    recordingsViewModel.performSemanticSearch(it)
                                },
                                placeholder = {
                                    Text("Search...", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f))
                                },
                                modifier = Modifier.weight(1f),
                                textStyle = TextStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimary),
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    cursorColor = MaterialTheme.colorScheme.onPrimary,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Semantic Search Indicator
                                        // [FIX] Only show icon if semantic search is enabled
                                        if (SettingsManager.semanticSearchEnabled) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = "Semantic Search Active",
                                                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = {
                                                searchQuery = ""
                                                recordingsViewModel.performSemanticSearch("")
                                            }) {
                                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onPrimary)
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.width(12.dp))
                                        }
                                    }
                                }
                            )
                        }
                    } else {
                        // --- Default Mode ---
                        Row(
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left: Menu + Title
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = onOpenDrawer) {
                                    Icon(
                                        Icons.Default.Menu,
                                        contentDescription = "Menu",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                                Text(
                                    text = titleText,
                                    fontFamily = Monospace,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }

                            // Right: Visualizer + Search
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                // Visualizer
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(vertical = 8.dp, horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isRecording && SettingsManager.showVisualizer) {
                                        AudioVisualizer(audioData = audioData)
                                    }
                                }

                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Navigation Logic
                    when(currentScreen) {
                        Screen.Home -> RecordingsScreen(viewModel = recordingsViewModel)
                        Screen.Starred -> StarredRecordingsScreen(viewModel = recordingsViewModel)
                        Screen.Tags -> TagsScreen(viewModel = recordingsViewModel)
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsDrawerContent(
        currentScreen: Screen = Screen.Home,
        onScreenSelected: (Screen) -> Unit = {}
    ) {
        var showChunkDialog by remember { mutableStateOf(false) }
        var showLanguageDialog by remember { mutableStateOf(false) }
        var showAsrModelDialog by remember { mutableStateOf(false) }
        var showManageDialog by remember { mutableStateOf(false) }
        var showFormatDialog by remember { mutableStateOf(false) }
        val context = LocalContext.current

        // Inject ViewModel for checking downloads
        val modelViewModel: ModelManagementViewModel = hiltViewModel()

        ModalDrawerSheet(windowInsets = WindowInsets.systemBars) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(12.dp))

                Text("Navigation", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Folder, null) },
                    label = { Text("All Recordings") },
                    selected = currentScreen == Screen.Home,
                    onClick = { onScreenSelected(Screen.Home) }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                    label = { Text("Tags") },
                    selected = currentScreen == Screen.Tags,
                    onClick = { onScreenSelected(Screen.Tags) }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Star, null) },
                    label = { Text("Starred") },
                    selected = currentScreen == Screen.Starred,
                    onClick = { onScreenSelected(Screen.Starred) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // --- App Preferences ---
                Text("Preferences", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)

                // Visualizer
                var visualizerState by remember { mutableStateOf(SettingsManager.showVisualizer) }
                NavigationDrawerItem(
                    label = { Text("Show Audio Visualizer") },
                    badge = { Switch(checked = visualizerState, onCheckedChange = { visualizerState = it; SettingsManager.showVisualizer = it }) },
                    selected = false,
                    onClick = { }
                )

                // Auto-Record
                var autoRecordState by remember { mutableStateOf(SettingsManager.autoRecordOnLaunch) }
                NavigationDrawerItem(
                    label = { Text("Auto-Record on Launch") },
                    badge = { Switch(checked = autoRecordState, onCheckedChange = { autoRecordState = it; SettingsManager.autoRecordOnLaunch = it }) },
                    selected = false,
                    onClick = { }
                )
                var autoRecordBootState by remember { mutableStateOf(SettingsManager.autoRecordOnBoot) }
                NavigationDrawerItem(
                    label = { Text("Auto-Record on Device Boot") },
                    badge = { Switch(checked = autoRecordBootState, onCheckedChange = { autoRecordBootState = it; SettingsManager.autoRecordOnBoot = it }) },
                    selected = false,
                    onClick = { }
                )

                // Keep Screen On
                var screenOnState by remember { mutableStateOf(SettingsManager.keepScreenOn) }
                NavigationDrawerItem(
                    label = { Text("Keep Screen On") },
                    badge = { Switch(checked = screenOnState, onCheckedChange = { screenOnState = it; SettingsManager.keepScreenOn = it; Toast.makeText(context, "Restart app to apply", Toast.LENGTH_SHORT).show() }) },
                    selected = false,
                    onClick = { }
                )

                // Haptic Feedback
                var hapticState by remember { mutableStateOf(SettingsManager.hapticFeedback) }
                NavigationDrawerItem(
                    label = { Text("Haptic Feedback") },
                    badge = { Switch(checked = hapticState, onCheckedChange = { hapticState = it; SettingsManager.hapticFeedback = it }) },
                    selected = false,
                    onClick = { }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // --- Advanced AI Processing ---
                Text("AI Processing", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)

                // 1. Noise Enhancement (Restored & "Smart")
                val noiseBundle = ModelRegistry.getBundle("bundle_enhancement")!!
                val noiseState by modelViewModel.getBundleState(noiseBundle).collectAsState()
                var asrEnhancementEnabled by remember { mutableStateOf(SettingsManager.asrEnhancementEnabled) }

                NavigationDrawerItem(
                    label = { Column { Text("Noise Reduction"); Text("Remove background noise", style = MaterialTheme.typography.labelSmall, color = Color.Gray) } },
                    selected = false,
                    badge = {
                        ModelDependentSwitch(
                            isChecked = asrEnhancementEnabled,
                            state = noiseState,
                            onCheckedChange = { isChecked ->
                                if (isChecked && !noiseState.isReady) {
                                    Toast.makeText(context, "Downloading Noise Reduction model...", Toast.LENGTH_SHORT).show()
                                    modelViewModel.downloadBundle(noiseBundle)
                                } else {
                                    asrEnhancementEnabled = isChecked
                                    SettingsManager.asrEnhancementEnabled = isChecked
                                }
                            }
                        )
                    },
                    onClick = { }
                )

                // 2. Speaker Diarization (Smart Toggle)
                val diarizationBundle = ModelRegistry.getBundle("bundle_diarization")!!
                val diarizationState by modelViewModel.getBundleState(diarizationBundle).collectAsState()
                var diarizationEnabled by remember { mutableStateOf(SettingsManager.speakerDiarizationEnabled) }

                NavigationDrawerItem(
                    label = { Column { Text("Speaker ID"); Text("Distinguish speakers", style = MaterialTheme.typography.labelSmall, color = Color.Gray) } },
                    selected = false,
                    badge = {
                        ModelDependentSwitch(
                            isChecked = diarizationEnabled,
                            state = diarizationState,
                            onCheckedChange = { isChecked ->
                                if (isChecked && !diarizationState.isReady) {
                                    Toast.makeText(context, "Downloading Diarization models...", Toast.LENGTH_SHORT).show()
                                    modelViewModel.downloadBundle(diarizationBundle)
                                } else {
                                    diarizationEnabled = isChecked
                                    SettingsManager.speakerDiarizationEnabled = isChecked
                                }
                            }
                        )
                    },
                    onClick = { }
                )

                // 3. Semantic Search (Smart Toggle)
                val searchBundle = ModelRegistry.getBundle("bundle_search")!!
                val searchState by modelViewModel.getBundleState(searchBundle).collectAsState()
                var searchEnabled by remember { mutableStateOf(SettingsManager.semanticSearchEnabled) }

                NavigationDrawerItem(
                    label = { Column { Text("Semantic Search"); Text("Enable AI search index", style = MaterialTheme.typography.labelSmall, color = Color.Gray) } },
                    selected = false,
                    badge = {
                        ModelDependentSwitch(
                            isChecked = searchEnabled,
                            state = searchState,
                            onCheckedChange = { isChecked ->
                                if (isChecked && !searchState.isReady) {
                                    Toast.makeText(context, "Downloading Search model...", Toast.LENGTH_SHORT).show()
                                    modelViewModel.downloadBundle(searchBundle)
                                } else {
                                    searchEnabled = isChecked
                                    SettingsManager.semanticSearchEnabled = isChecked
                                }
                            }
                        )
                    },
                    onClick = { }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // --- Management Items ---
                Text("Models & Storage", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)

                NavigationDrawerItem(
                    label = { Text("Manage AI Models") },
                    selected = false,
                    onClick = { showManageDialog = true }
                )
                NavigationDrawerItem(
                    label = { Text("Recording Format") },
                    badge = { Text(SettingsManager.recordingFormat.name, style = MaterialTheme.typography.labelSmall) },
                    selected = false,
                    onClick = { showFormatDialog = true }
                )
                NavigationDrawerItem(
                    label = { Text("Chunk Duration") },
                    selected = false,
                    onClick = { showChunkDialog = true }
                )
                NavigationDrawerItem(
                    label = { Text("ASR Language") },
                    selected = false,
                    onClick = { showLanguageDialog = true }
                )
                NavigationDrawerItem(
                    label = { Text("ASR Model") },
                    selected = false,
                    onClick = { showAsrModelDialog = true }
                )
            }
        }

        // --- Dialogs ---
        if (showFormatDialog) RecordingFormatDialog({ showFormatDialog = false }) { SettingsManager.recordingFormat = it }
        if (showChunkDialog) ListPreferenceDialog("chunk_duration", "Recording Chunk Duration", R.array.chunk_duration_entries, R.array.chunk_duration_values) { showChunkDialog = false }
        if (showLanguageDialog) ListPreferenceDialog("asr_language", "Transcription Language", R.array.asr_language_entries, R.array.asr_language_values) { showLanguageDialog = false }
        if (showAsrModelDialog) AsrModelSelectionDialog({ showAsrModelDialog = false }, modelViewModel)
        if (showManageDialog) ModelManagementDialog({ showManageDialog = false }, modelViewModel)
    }

    @Composable
    private fun ModelDependentSwitch(
        isChecked: Boolean,
        state: com.example.allrecorder.ui.components.BundleUiState, // Ensure this matches your ViewModel's state class
        onCheckedChange: (Boolean) -> Unit
    ) {
        if (state.isDownloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else {
            // Force switch to OFF if model is not ready, unless we are currently checking it to download
            val uiChecked = isChecked && state.isReady

            Switch(
                checked = uiChecked,
                onCheckedChange = onCheckedChange,
                thumbContent = if (!state.isReady) {
                    { Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = SwitchDefaults.colors(
                    uncheckedThumbColor = if(!state.isReady) MaterialTheme.colorScheme.primary else Color.Unspecified
                )
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
                            val state by viewModel.getBundleState(bundle).collectAsState()
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

                                if (state.isDownloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else if (!state.isReady) {
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
    fun RecordingFormatDialog(
        onDismiss: () -> Unit,
        onFormatSelected: (SettingsManager.RecordingFormat) -> Unit
    ) {
        val currentFormat = SettingsManager.recordingFormat

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Recording Format") },
            text = {
                Column {
                    SettingsManager.RecordingFormat.values().forEach { format ->
                        val isSelected = (format == currentFormat)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onFormatSelected(format)
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onFormatSelected(format)
                                    onDismiss()
                                }
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    text = if (format == SettingsManager.RecordingFormat.WAV) "WAV (High Quality)" else "M4A (AAC)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if(isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = if (format == SettingsManager.RecordingFormat.WAV)
                                        "Uncompressed. Large file size. Instant waveform."
                                    else
                                        "Compressed. Small file size (10x smaller).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestPermissions() {
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )
    }
}