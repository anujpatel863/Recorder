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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.lifecycle.viewmodel.compose.viewModel
// [FIX] Updated import for hiltViewModel (moved from androidx.hilt.navigation.compose)
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.allrecorder.models.ModelRegistry
import com.example.allrecorder.recordings.AudioVisualizer
import com.example.allrecorder.recordings.RecordingsScreen
import com.example.allrecorder.recordings.StarredRecordingsScreen
import com.example.allrecorder.recordings.RecordingsViewModel
import com.example.allrecorder.ui.components.ModelManagementDialog
import com.example.allrecorder.ui.components.ModelManagementViewModel
// [FIX] Ensure BundleUiState is imported (if it's in a different package, though usually same package as VM)
import com.example.allrecorder.ui.components.BundleUiState
import com.example.allrecorder.ui.theme.AllRecorderTheme
import com.example.allrecorder.ui.theme.Monospace
import com.example.allrecorder.ui.theme.RetroPrimary
import com.example.allrecorder.ui.theme.RetroPrimaryDark
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

enum class Screen {
    Home, Starred
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
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = "Semantic Search Active",
                                            tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp)
                                        )

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
                                    if (isRecording) {
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

        var asrEnhancementEnabled by remember {
            mutableStateOf(SettingsManager.asrEnhancementEnabled)
        }

        val context = LocalContext.current

        // [FIX] Inject ModelManagementViewModel properly
        val modelViewModel: ModelManagementViewModel = hiltViewModel()

        ModalDrawerSheet(windowInsets = WindowInsets.systemBars) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(12.dp))

                // --- Navigation Section ---
                Text("Navigation", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Folder, null) },
                    label = { Text("All Recordings") },
                    selected = currentScreen == Screen.Home,
                    onClick = { onScreenSelected(Screen.Home) }
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Star, null) },
                    label = { Text("Starred") },
                    selected = currentScreen == Screen.Starred,
                    onClick = { onScreenSelected(Screen.Starred) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // --- Existing Settings ---
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

                NavigationDrawerItem(
                    label = { Text("ASR Model") },
                    selected = false,
                    onClick = { showAsrModelDialog = true }
                )

                val noiseBundle = ModelRegistry.getBundle("bundle_enhancement")!!
                // [FIX] This line should now compile correctly because BundleUiState is known
                val noiseState by modelViewModel.getBundleState(noiseBundle).collectAsState()

                NavigationDrawerItem(
                    label = { Text("Noise Reduction") },
                    selected = false,
                    badge = {
                        // [FIX] These properties (isDownloading, isReady) are now valid
                        if (noiseState.isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Switch(
                                checked = asrEnhancementEnabled && noiseState.isReady,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) {
                                        if (noiseState.isReady) {
                                            asrEnhancementEnabled = true
                                            SettingsManager.prefs.edit { putBoolean("asr_enhancement", true) }
                                        } else {
                                            Toast.makeText(context, "Downloading Noise Reduction model...", Toast.LENGTH_SHORT).show()
                                            modelViewModel.downloadBundle(noiseBundle)
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

        // ... (Dialogs remain unchanged) ...
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