package com.example.allrecorder.models

data class ModelSpec(
    val id: String,
    val fileName: String,
    val url: String,
    val type: ModelType,
    val description: String,
    val sha256: String? = null
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
            description = "Tiny Encoder (Fastest)",
            sha256 ="d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"
        ),
        ModelSpec(
            id = "tiny_decoder",
            fileName = "tiny-decoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
            type = ModelType.ASR,
            description = "Tiny Decoder",
            sha256 ="d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"
        ),

        // --- Whisper Base (English) ---
        ModelSpec(
            id = "base_encoder",
            fileName = "base-encoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx",
            type = ModelType.ASR,
            description = "Base Encoder (Balanced)",
            sha256 ="0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"
        ),
        ModelSpec(
            id = "base_decoder",
            fileName = "base-decoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-decoder.int8.onnx",
            type = ModelType.ASR,
            description = "Base Decoder",
            sha256 ="9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"
        ),

        // --- Whisper Small (English) ---
        ModelSpec(
            id = "small_encoder",
            fileName = "small-encoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
            type = ModelType.ASR,
            description = "Small Encoder (Accurate)",
            sha256 ="4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"
        ),
        ModelSpec(
            id = "small_decoder",
            fileName = "small-decoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
            type = ModelType.ASR,
            description = "Small Decoder",
            sha256 ="acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee\n"
        ),

        // --- Whisper Medium (English) ---
        ModelSpec(
            id = "medium_encoder",
            fileName = "medium-encoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-encoder.int8.onnx",
            type = ModelType.ASR,
            description = "Medium Encoder (Best Accuracy, Slow)",
            sha256 ="1c54582b4d829de0089f6cb63bbbdb3bf7555398bacaf855fbecf1a84dfd193e"
        ),
        ModelSpec(
            id = "medium_decoder",
            fileName = "medium-decoder.int8.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/medium-decoder.int8.onnx",
            type = ModelType.ASR,
            description = "Medium Decoder",
            sha256 ="595d00a338a365a7bfa0ca7f296cabc639583bef770ab6130df90f49a6412747"
        ),

        // --- Shared Tokens ---
        ModelSpec(
            id = "whisper_tokens",
            fileName = "whisper-tokens", // Renamed from tokens.txt to match your screenshot
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt",
            type = ModelType.ASR,
            description = "Token vocabulary",
            sha256 ="B34B360DBB493E781E479794586D661700670D65564001F23024971D1F2FA126"
        ),

        // --- Diarization & VAD ---
        ModelSpec(
            id = "segmentation",
            fileName = "segmentation.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx",
            type = ModelType.DIARIZATION,
            description = "Speaker Segmentation",
            sha256 ="d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d"
        ),
        ModelSpec(
            id = "speaker_embed",
            fileName = "wespeaker_en_voxceleb_resnet34_LM.onnx",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx",
            type = ModelType.DIARIZATION,
            description = "Speaker Embedding",
            sha256 ="E9848563DA86F263117134DFD7AD63C92355B37DE492B55E325400C9D9C39012"
        ),
        ModelSpec(
            id = "silero_vad",
            fileName = "silero_vad.onnx",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            type = ModelType.VAD,
            description = "Voice Activity Detection",
            sha256 ="A35EBF52FD3CE5F1469B2A36158DBA761BC47B973EA3382B3186CA15B1F5AF28"
        ),

        // --- Noise Cancellation ---
        ModelSpec(
            id = "gtcrn",
            fileName = "gtcrn_simple.onnx",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx",
            type = ModelType.ENHANCEMENT,
            description = "Noise Reduction",
            sha256 ="E77603AC0C23DAC3227DD2D7135B3A585CBEE2679048AECFA886657D3AE1B534"
        ),

        // --- Semantic Search ---
        ModelSpec(
            id = "universal_sentence_encoder",
            fileName = "universal_sentence_encoder.tflite",
            // This is the official quantized version (approx 6MB)
            url = "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/1/universal_sentence_encoder.tflite",
            type = ModelType.EMBEDDING,
            description = "Semantic Search Model",
            sha256 ="89AD3C74175DD8CAA398CC22B657296D94302D20C525C12B58B29420F7249749"
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