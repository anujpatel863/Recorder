package com.example.allrecorder.models

data class ModelSpec(
    val id: String,
    val fileName: String,
    val url: String,
    val type: ModelType,
    val description: String
)
data class ModelBundle(
    val id: String, // e.g., "bundle_asr_tiny"
    val name: String, // e.g., "Tiny (English)"
    val description: String,
    val modelIds: List<String> // List of file IDs from ModelSpec
) {
    // Helper to calculate total size string
    fun getTotalSize(specs: List<ModelSpec>): String {
        val totalBytes = specs.filter { it.id in modelIds }.sumOf {
            // You might want to add a sizeBytes long to ModelSpec in the future
            // For now, we'll just use a placeholder or parse the string if you added it
            0L
        }
        return "Unknown Size" // Update this if you add byte sizes to ModelSpec
    }
}

enum class ModelType { ASR, DIARIZATION, VAD, ENHANCEMENT, EMBEDDING }

object ModelRegistry {

    val availableModels = listOf(
        // --- Whisper Tiny (English) ---
        ModelSpec(
            id = "tiny_encoder",
            fileName = "tiny-encoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
            type = ModelType.ASR,
            description = "Tiny Encoder (Fastest)"
        ),
        ModelSpec(
            id = "tiny_decoder",
            fileName = "tiny-decoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
            type = ModelType.ASR,
            description = "Tiny Decoder"
        ),

        // --- Whisper Base (English) ---
        ModelSpec(
            id = "base_encoder",
            fileName = "base-encoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx",
            type = ModelType.ASR,
            description = "Base Encoder (Balanced)"
        ),
        ModelSpec(
            id = "base_decoder",
            fileName = "base-decoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-decoder.int8.onnx",
            type = ModelType.ASR,
            description = "Base Decoder"
        ),

        // --- Whisper Small (English) ---
        ModelSpec(
            id = "small_encoder",
            fileName = "small-encoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
            type = ModelType.ASR,
            description = "Small Encoder (Accurate)"
        ),
        ModelSpec(
            id = "small_decoder",
            fileName = "small-decoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
            type = ModelType.ASR,
            description = "Small Decoder"
        ),

        // --- Whisper Medium (English) ---
        ModelSpec(
            id = "medium_encoder",
            fileName = "medium-encoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-encoder.int8.onnx",
            type = ModelType.ASR,
            description = "Medium Encoder (Best Accuracy, Slow)"
        ),
        ModelSpec(
            id = "medium_decoder",
            fileName = "medium-decoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-decoder.int8.onnx",
            type = ModelType.ASR,
            description = "Medium Decoder"
        ),

        // --- Shared Tokens ---
        ModelSpec(
            id = "whisper_tokens",
            fileName = "whisper-tokens", // Renamed from tokens.txt to match your screenshot
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt",
            type = ModelType.ASR,
            description = "Token vocabulary"
        ),

        // --- Diarization & VAD ---
        ModelSpec(
            id = "segmentation",
            fileName = "segmentation.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx",
            type = ModelType.DIARIZATION,
            description = "Speaker Segmentation"
        ),
        ModelSpec(
            id = "speaker_embed",
            fileName = "wespeaker_en_voxceleb_resnet34_LM.onnx",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx",
            type = ModelType.DIARIZATION,
            description = "Speaker Embedding"
        ),
        ModelSpec(
            id = "silero_vad",
            fileName = "silero_vad.onnx",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            type = ModelType.VAD,
            description = "Voice Activity Detection"
        ),

        // --- Noise Cancellation ---
        ModelSpec(
            id = "gtcrn",
            fileName = "gtcrn_simple.onnx",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx",
            type = ModelType.ENHANCEMENT,
            description = "Noise Reduction"
        ),

        // --- Semantic Search ---
        ModelSpec(
            id = "universal_sentence_encoder",
            fileName = "universal_sentence_encoder.tflite",
            // This is the official quantized version (approx 6MB)
            url = "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/1/universal_sentence_encoder.tflite",
            type = ModelType.EMBEDDING,
            description = "Semantic Search Model"
        )
    )

    fun getSpec(id: String) = availableModels.first { it.id == id }
    fun getSpecByFileName(name: String) = availableModels.firstOrNull { it.fileName == name }
    val bundles = listOf(
        ModelBundle(
            id = "bundle_asr_tiny",
            name = "Tiny Model (Fastest)",
            description = "Standard English transcription. Approx 40MB.",
            modelIds = listOf("tiny_encoder", "tiny_decoder", "whisper_tokens")
        ),
        ModelBundle(
            id = "bundle_asr_base",
            name = "Base Model (Balanced)",
            description = "Better accuracy than Tiny. Approx 140MB.",
            modelIds = listOf("base_encoder", "base_decoder", "whisper_tokens")
        ),
        ModelBundle(
            id = "bundle_asr_small",
            name = "Small Model (High Accuracy)",
            description = "Best for clear dictation. Slow on older phones. Approx 400MB.",
            modelIds = listOf("small_encoder", "small_decoder", "whisper_tokens")
        ),
        ModelBundle(
            id = "bundle_diarization",
            name = "Speaker Identification",
            description = "Distinguish between Speaker A and Speaker B.",
            modelIds = listOf("segmentation", "speaker_embed")
        ),
        ModelBundle(
            id = "bundle_enhancement",
            name = "Noise Reduction",
            description = "Removes background noise before transcription.",
            modelIds = listOf("gtcrn")
        ),
        ModelBundle(
            id = "bundle_search",
            name = "Semantic Search",
            description = "Enables searching by meaning.",
            modelIds = listOf("universal_sentence_encoder")
        )
    )

    fun getBundle(id: String) = bundles.find { it.id == id }
}