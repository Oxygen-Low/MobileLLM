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
    val modelScopeRepo: String? = null,
    val modelScopeFile: String? = null,
    val modelScopeUrl: String? = null,
    val downloadSizeMb: Int = 1850,
    val downloadState: ModelDownloadState = ModelDownloadState.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f
) {
    val isReadyToLoad: Boolean
        get() = downloadState == ModelDownloadState.DOWNLOADED

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
            modelScopeRepo = "OxygenLow/DeepSmolLM3-3B",
            modelScopeFile = "DeepSmolLM3-3B-Q4_K_M.gguf",
            modelScopeUrl = "https://modelscope.ai/models/OxygenLow/DeepSmolLM3-3B",
            description = "",
            supportsReasoning = true,
            downloadState = ModelDownloadState.NOT_DOWNLOADED
        )

        val DEFAULT_MODELS = listOf(DEEP_SMOLLM3)
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
    val repo: String,
    val file: String,
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
            repo = "modelscope/whisper.cpp",
            file = "ggml-tiny.bin",
            description = ""
        )

        val PIPER_TTS = AudioModelProfile(
            id = "piper_voice_tts",
            name = "Piper Fast Neural TTS",
            type = AudioModelType.TTS,
            sizeMb = 28,
            repo = "modelscope/piper-voices",
            file = "en_US-lessac-medium.onnx",
            description = ""
        )
    }
}
