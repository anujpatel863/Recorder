package com.example.allrecorder

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.allrecorder.ui.detail.ConversationDetailScreen
import com.example.allrecorder.ui.detail.ConversationDetailViewModel
import com.example.allrecorder.ui.theme.AllRecorderTheme

class ConversationDetailActivity : ComponentActivity() {

    private val conversationId: Long by lazy {
        intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)
    }

    // Use a Factory to pass the conversationId to the ViewModel
    private val viewModel: ConversationDetailViewModel by viewModels {
        ConversationDetailViewModelFactory(application, conversationId)
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"

        fun newIntent(context: Context, conversationId: Long): Intent {
            return Intent(context, ConversationDetailActivity::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AllRecorderTheme {
                val playerState by viewModel.playerState.collectAsState()
                val recording by viewModel.recording.collectAsState()

                ConversationDetailScreen(
                    recording = recording,
                    playerState = playerState,
                    onNavigateUp = { finish() },
                    onPlayPause = viewModel::onPlayPauseClicked,
                    onRewind = viewModel::onRewind,
                    onForward = viewModel::onForward,
                    onSeek = viewModel::onSeek
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop playback when the activity is paused
        viewModel.stopPlayback()
    }
}

// Factory for providing conversationId to ViewModel
class ConversationDetailViewModelFactory(
    private val application: Application,
    private val conversationId: Long
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ConversationDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ConversationDetailViewModel(application, conversationId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
