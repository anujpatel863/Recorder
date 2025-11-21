package com.example.allrecorder.ui.components

import android.app.Application
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.allrecorder.models.ModelBundle
import com.example.allrecorder.models.ModelManager
import com.example.allrecorder.models.ModelRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Distinct states to prevent UI glitches
data class BundleUiState(
    val isReady: Boolean = false,       // True only if ALL files exist on disk
    val isDownloading: Boolean = false, // True only if WorkManager is actively running
    val progress: Float = 0f            // 0.0 to 1.0 (Aggregate of all files)
)

class ModelManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val modelManager = ModelManager(application)
    private val workManager = WorkManager.getInstance(application)

    // Force UI to re-read file system
    var refreshTrigger by androidx.compose.runtime.mutableStateOf(0)

    fun downloadBundle(bundle: ModelBundle) {
        modelManager.downloadBundle(bundle)
        forceRefresh()
    }

    fun deleteOrCancelBundle(bundle: ModelBundle) {
        modelManager.deleteBundle(bundle) // Cancels work AND deletes tmp/final files
        forceRefresh()
    }

    private fun forceRefresh() {
        viewModelScope.launch {
            delay(200) // Allow IO to propagate
            refreshTrigger++
        }
    }

    /**
     * Returns a single State Object that tells the UI exactly what to render.
     * No more guessing based on float values.
     */
    fun getBundleState(bundle: ModelBundle): LiveData<BundleUiState> {
        val mediator = MediatorLiveData<BundleUiState>()
        val modelIds = bundle.modelIds

        // Trackers for each file in the bundle
        val fileProgressMap = mutableMapOf<String, Float>()
        val fileActiveMap = mutableMapOf<String, Boolean>()

        fun computeState() {
            // 1. Physical Check (The Source of Truth)
            val allFilesReady = modelManager.isBundleReady(bundle)

            // 2. Active Download Check
            // We are downloading ONLY if at least one file has a running/enqueued worker
            val isAnyDownloading = fileActiveMap.values.any { it }

            // 3. Progress Math
            var totalScore = 0f
            val maxScore = modelIds.size * 100f

            modelIds.forEach { id ->
                val spec = ModelRegistry.getSpec(id)
                if (modelManager.isModelReady(spec)) {
                    totalScore += 100f
                } else {
                    // If not ready, add the partial download %
                    totalScore += (fileProgressMap[id] ?: 0f)
                }
            }

            val aggregatedProgress = (totalScore / maxScore).coerceIn(0f, 1f)

            // 4. Final State Decision
            mediator.value = BundleUiState(
                isReady = allFilesReady,
                // If files are ready, we are NOT downloading.
                // If files are NOT ready, we are downloading only if workers are active.
                isDownloading = !allFilesReady && isAnyDownloading,
                progress = aggregatedProgress
            )
        }

        // Initialize Map
        modelIds.forEach {
            fileProgressMap[it] = 0f
            fileActiveMap[it] = false
        }

        // Observe WorkManager
        modelIds.forEach { id ->
            val workLiveData = workManager.getWorkInfosByTagLiveData(id)
            mediator.addSource(workLiveData) { workInfos ->
                val activeWork = workInfos?.find { !it.state.isFinished }

                if (activeWork != null) {
                    fileActiveMap[id] = true
                    val p = activeWork.progress.getInt("progress", 0)
                    fileProgressMap[id] = p.toFloat()
                } else {
                    fileActiveMap[id] = false
                    // If finished successfully, progress is logically 100 (handled by isModelReady check in computeState)
                    // If failed/cancelled, progress remains 0 or last known value
                }
                computeState()
            }
        }

        // Initial computation
        computeState()

        return mediator
    }
}