package com.example.allrecorder

import android.content.Context
import android.util.Log
import com.example.allrecorder.models.ModelManager
import com.example.allrecorder.models.ModelRegistry
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

// [FIX] Accept ModelManager in constructor
class EmbeddingManager(
    private val context: Context,
    private val modelManager: ModelManager
) {

    private var textEmbedder: TextEmbedder? = null
    // [FIX] Removed manual instantiation: private val modelManager = ModelManager(context)

    init {
        setupEmbedder()
    }

    private fun setupEmbedder() {
        try {
            val spec = ModelRegistry.getSpec("universal_sentence_encoder")

            if (!modelManager.isModelReady(spec)) {
                Log.w("EmbeddingManager", "Model not downloaded yet. Semantic search unavailable.")
                return
            }

            val modelPath = modelManager.getModelPath(spec)

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelPath)
                .build()

            val options = TextEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .build()

            textEmbedder = TextEmbedder.createFromOptions(context, options)
            Log.i("EmbeddingManager", "Initialized successfully.")

        } catch (e: Exception) {
            Log.e("EmbeddingManager", "Failed to initialize", e)
        }
    }

    suspend fun generateEmbedding(text: String): List<Float>? = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext null

        if (textEmbedder == null) {
            setupEmbedder()
            if (textEmbedder == null) return@withContext null
        }

        try {
            val result = textEmbedder?.embed(text)
            val floatArray = result?.embeddingResult()?.embeddings()?.firstOrNull()?.floatEmbedding()
            return@withContext floatArray?.toList()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    fun cosineSimilarity(vec1: List<Float>, vec2: List<Float>): Float {
        if (vec1.size != vec2.size) return 0f

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            normA += vec1[i] * vec1[i]
            normB += vec2[i] * vec2[i]
        }

        if (normA == 0.0 || normB == 0.0) return 0f
        return (dotProduct / (sqrt(normA) * sqrt(normB))).toFloat()
    }

    fun close() {
        textEmbedder?.close()
    }
}