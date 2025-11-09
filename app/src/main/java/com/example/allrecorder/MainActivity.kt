package com.example.allrecorder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.allrecorder.ui.conversations.ConversationsScreen
import com.example.allrecorder.ui.recordings.RecordingsScreen
import com.example.allrecorder.ui.theme.AllRecorderTheme
import com.example.allrecorder.ui.theme.Monospace
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val recordAudioGranted = permissions.getOrDefault(Manifest.permission.RECORD_AUDIO, false)
            if (!recordAudioGranted) {
                Toast.makeText(this, "Audio recording permission is required to use this app.", Toast.LENGTH_LONG).show()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display to hide system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Initialize SettingsManager
        SettingsManager.init(this)

        if (!hasPermissions()) {
            requestPermissions()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // This callback is always enabled
            override fun handleOnBackPressed() {
                // We just finish the activity, as there's no fragment backstack
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
            // Hide the navigation bars and make them appear only on swipe.
            windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                SettingsDrawerContent(onClose = { scope.launch { drawerState.close() } })
            }
        ) {
            MainContent(onOpenDrawer = { scope.launch { drawerState.open() } })
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun MainContent(onOpenDrawer: () -> Unit) {
        val pagerState = rememberPagerState(pageCount = { 2 })
        val scope = rememberCoroutineScope()
        val tabTitles = listOf("Conversations", "Recordings")

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
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        ) { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues)) {
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(title, fontFamily = Monospace) }
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> {
                            // Get the context within the composable
                            val context = LocalContext.current
                            ConversationsScreen(
                                onConversationClick = { conversationId ->
                                    val intent = ConversationDetailActivity.newIntent(context, conversationId)
                                    startActivity(intent)
                                }
                            )
                        }
                        1 -> RecordingsScreen()
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsDrawerContent(onClose: () -> Unit) {
        // State for managing dialog visibility
        var showChunkDialog by remember { mutableStateOf(false) }
        var showDiarizationDialog by remember { mutableStateOf(false) }
        var showSilenceDialog by remember { mutableStateOf(false) }
        var showStrictnessDialog by remember { mutableStateOf(false) }
        var showLanguageDialog by remember { mutableStateOf(false) }
        var showDecoderDialog by remember { mutableStateOf(false) }

        ModalDrawerSheet(windowInsets = WindowInsets.systemBars) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(12.dp))

                Text("Recording", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                NavigationDrawerItem(
                    label = { Text("Recording Chunk Duration") },
                    selected = false,
                    onClick = { showChunkDialog = true }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Processing", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                NavigationDrawerItem(
                    label = { Text("Diarization Model") },
                    selected = false,
                    onClick = { showDiarizationDialog = true }
                )
                NavigationDrawerItem(
                    label = { Text("Silence Sensitivity") },
                    selected = false,
                    onClick = { showSilenceDialog = true }
                )
                NavigationDrawerItem(
                    label = { Text("Speaker Detection Strictness") },
                    selected = false,
                    onClick = { showStrictnessDialog = true }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Transcription (ASR)", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                NavigationDrawerItem(
                    label = { Text("Transcription Language") },
                    selected = false,
                    onClick = { showLanguageDialog = true }
                )
                NavigationDrawerItem(
                    label = { Text("Decoder Type") },
                    selected = false,
                    onClick = { showDecoderDialog = true }
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
        if (showDiarizationDialog) {
            ListPreferenceDialog(
                key = "diarization_model",
                title = "Diarization Model",
                entriesResId = R.array.diarization_model_entries,
                entryValuesResId = R.array.diarization_model_values,
                onDismiss = { showDiarizationDialog = false }
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
        if (showDecoderDialog) {
            ListPreferenceDialog(
                key = "asr_decoder",
                title = "Decoder Type",
                entriesResId = R.array.asr_decoder_entries,
                entryValuesResId = R.array.asr_decoder_values,
                onDismiss = { showDecoderDialog = false }
            )
        }
        if (showSilenceDialog) {
            SeekBarPreferenceDialog(
                key = "silence_sensitivity",
                title = "Silence Sensitivity",
                min = 3, max = 30, default = 10,
                onDismiss = { showSilenceDialog = false }
            )
        }
        if (showStrictnessDialog) {
            SeekBarPreferenceDialog(
                key = "speaker_strictness",
                title = "Speaker Detection Strictness",
                min = 70, max = 95, default = 85,
                onDismiss = { showStrictnessDialog = false }
            )
        }
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
        val currentValue = SettingsManager.prefs.getString(key, entryValues[0])
        val (selected, setSelected) = remember { mutableStateOf(currentValue) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    entries.forEachIndexed { index, entry ->
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

    @Composable
    private fun SeekBarPreferenceDialog(
        key: String,
        title: String,
        min: Int,
        max: Int,
        default: Int,
        onDismiss: () -> Unit
    ) {
        val currentValue = SettingsManager.prefs.getInt(key, default)
        var sliderValue by remember { mutableStateOf(currentValue) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(sliderValue.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Slider(
                        value = sliderValue.toFloat(),
                        onValueChange = { sliderValue = it.toInt() },
                        valueRange = min.toFloat()..max.toFloat(),
                        steps = (max - min) - 1
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        SettingsManager.prefs.edit { putInt(key, sliderValue) }
                        onDismiss()
                    }
                ) {
                    Text("Set")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Permission Handling ---
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