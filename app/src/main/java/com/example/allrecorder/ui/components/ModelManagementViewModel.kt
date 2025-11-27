package com.example.allrecorder.ui.components

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.allrecorder.models.ModelBundle
import com.example.allrecorder.models.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import javax.inject.Inject

// [FIX] Define this class so other files can see it
data class BundleUiState(
    val isReady: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null
)

@HiltViewModel
class ModelManagementViewModel @Inject constructor(
    application: Application,
    private val modelManager: ModelManager
) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)

    // Cache the flows to prevent recreating them constantly
    private val stateFlows = mutableMapOf<String, StateFlow<BundleUiState>>()

    fun getBundleState(bundle: ModelBundle): StateFlow<BundleUiState> {
        return stateFlows.getOrPut(bundle.id) {
            createBundleFlow(bundle)
        }
    }

    private fun createBundleFlow(bundle: ModelBundle): StateFlow<BundleUiState> {
        // 1. Observe WorkManager by BUNDLE TAG
        val workInfoFlow = workManager.getWorkInfosByTagFlow(bundle.id)

        // 2. Create a ticker to periodically check file existence
        val fileCheckFlow = flow {
            while (true) {
                emit(Unit)
                delay(2000) // Check disk every 2 seconds
            }
        }

        return combine(workInfoFlow, fileCheckFlow) { workInfos, _ ->
            val isReady = modelManager.isBundleReady(bundle)

            val activeWorkers = workInfos.filter { !it.state.isFinished }
            val isDownloading = activeWorkers.isNotEmpty()

            // Calculate aggregated progress
            var progress = 0f
            if (isDownloading) {
                val totalWorkers = bundle.modelIds.size
                val progressSum = activeWorkers.sumOf {
                    it.progress.getInt("progress", 0)
                }
                // Add 100% for workers that finished already in this session
                val finishedCount = workInfos.count { it.state == WorkInfo.State.SUCCEEDED }

                val totalScore = progressSum + (finishedCount * 100)
                progress = (totalScore.toFloat() / (totalWorkers * 100)).coerceIn(0f, 1f)
            }

            BundleUiState(
                isReady = isReady,
                isDownloading = isDownloading && !isReady,
                progress = progress
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BundleUiState(isReady = modelManager.isBundleReady(bundle))
        )
    }

    fun downloadBundle(bundle: ModelBundle) {
        modelManager.downloadBundle(bundle)
    }

    fun deleteBundle(bundle: ModelBundle) {
        modelManager.deleteBundle(bundle)
    }
}