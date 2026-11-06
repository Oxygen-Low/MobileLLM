package com.example.model

enum class ModelDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

data class ModelProfile(
    val id: String,
    val name: String,
    val shortName: String,
    val format: ModelFormat,
    val quantization: String,
    val parameters: String,
    val contextLength: Int,
    val memoryMb: Int,
    val description: String = "",
    val supportsReasoning: Boolean,
    val huggingFaceRepo: String? = null,
    val huggingFaceFile: String? = null,
    val downloadSizeMb: Int = 1800,
    val downloadState: ModelDownloadState = ModelDownloadState.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f,
    val isCustom: Boolean = false,
    val customFileName: String? = null,
    val customFileSize: Long = 0L,
    val customMetadata: Map<String, String> = emptyMap()
) {
    val isReadyToLoad: Boolean
        get() = downloadState == ModelDownloadState.DOWNLOADED || isCustom

    companion object {
        val DEEP_SMOLLM3 = ModelProfile(
            id = "deepsmollm3",
            name = "DeepSmolLM3 (DeepSeek v4.1 Reasoning)",
            shortName = "DeepSmolLM3",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            parameters = "3B",
            contextLength = 8192,
            memoryMb = 1850,
            downloadSizeMb = 1850,
            huggingFaceRepo = "HuggingFaceTB/SmolLM3-3B-DeepReasoning-GGUF",
            huggingFaceFile = "deepsmollm3-3b-q4_k_m.gguf",
            description = "",
            supportsReasoning = true,
            downloadState = ModelDownloadState.NOT_DOWNLOADED
        )

        val SMOLLM3 = ModelProfile(
            id = "smollm3",
            name = "SmolLM3 Base (3B Instruct)",
            shortName = "SmolLM3",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            parameters = "3B",
            contextLength = 4096,
            memoryMb = 1750,
            downloadSizeMb = 1790,
            huggingFaceRepo = "HuggingFaceTB/SmolLM3-3B-Instruct-GGUF",
            huggingFaceFile = "smollm3-3b-instruct-q4_k_m.gguf",
            description = "",
            supportsReasoning = false,
            downloadState = ModelDownloadState.NOT_DOWNLOADED
        )

        val DEFAULT_MODELS = listOf(DEEP_SMOLLM3, SMOLLM3)
    }
}

enum class AudioModelType {
    STT, // Speech to Text
    TTS  // Text to Speech
}

data class AudioModelProfile(
    val id: String,
    val name: String,
    val type: AudioModelType,
    val sizeMb: Int,
    val huggingFaceRepo: String,
    val huggingFaceFile: String,
    val description: String = "",
    val downloadState: ModelDownloadState = ModelDownloadState.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f
) {
    companion object {
        val WHISPER_STT = AudioModelProfile(
            id = "whisper_tiny_stt",
            name = "Whisper-Tiny Mobile STT",
            type = AudioModelType.STT,
            sizeMb = 39,
            huggingFaceRepo = "ggerganov/whisper.cpp",
            huggingFaceFile = "ggml-tiny.bin",
            description = ""
        )

        val PIPER_TTS = AudioModelProfile(
            id = "piper_voice_tts",
            name = "Piper Fast Neural TTS",
            type = AudioModelType.TTS,
            sizeMb = 28,
            huggingFaceRepo = "rhasspy/piper-voices",
            huggingFaceFile = "en_US-lessac-medium.onnx",
            description = ""
        )
    }
}
