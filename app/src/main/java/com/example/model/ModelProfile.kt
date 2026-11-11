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
    val downloadSizeMb: Int = 5870,
    val downloadState: ModelDownloadState = ModelDownloadState.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f
) {
    val isReadyToLoad: Boolean
        get() = downloadState == ModelDownloadState.DOWNLOADED

    companion object {
        val DEEP_SMOLLM3 = ModelProfile(
            id = "deepsmollm3",
            name = "DeepSmolLM3 (BF16)",
            shortName = "DeepSmolLM3",
            format = ModelFormat.GGUF,
            quantization = "BF16",
            parameters = "3B",
            contextLength = 8192,
            memoryMb = 5870,
            downloadSizeMb = 5870,
            modelScopeRepo = "Oxygen-Low/DeepSmolLM3-3B",
            modelScopeFile = "DeepSmolLM3-3B.BF16.gguf",
            modelScopeUrl = "https://modelscope.cn/models/Oxygen-Low/DeepSmolLM3-3B",
            description = "3B bfloat16 (BF16) mobile GGUF model with DeepSeek v4.1-style dual-mode reasoning (<think>...</think>)",
            supportsReasoning = true,
            downloadState = ModelDownloadState.NOT_DOWNLOADED
        )

        val DEFAULT_MODELS = listOf(DEEP_SMOLLM3)
    }
}
