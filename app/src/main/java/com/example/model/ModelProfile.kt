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
    /** ModelScope repository slug, used as the first download source. */
    val modelScopeRepo: String? = null,
    val modelScopeFile: String? = null,
    val modelScopeUrl: String? = null,
    /** Hugging Face repository slug, used as a fallback download source. */
    val hfRepo: String? = null,
    val hfFile: String? = null,
    val downloadSizeMb: Int = 0,
    val downloadState: ModelDownloadState = ModelDownloadState.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f
) {
    val isReadyToLoad: Boolean
        get() = downloadState == ModelDownloadState.DOWNLOADED

    companion object {
        /**
         * The only model currently shipped by the app.
         *
         * Q4_K_M is the smallest quantisation of this checkpoint that keeps
         * output quality intact while fitting a 3B model in ~2 GB of RAM.
         * Swap [modelScopeFile]/[hfFile] for `DeepSmolLM3-3B.BF16.gguf`
         * (5873 MB) on devices with enough memory.
         */
        val DEEP_SMOLLM3 = ModelProfile(
            id = "deepsmollm3",
            name = "DeepSmolLM3-3B",
            shortName = "DeepSmolLM3",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            parameters = "3B",
            contextLength = 8192,
            memoryMb = 2300,
            downloadSizeMb = 1827,
            modelScopeRepo = "Oxygen-Low/DeepSmolLM3-3B",
            modelScopeFile = "DeepSmolLM3-3B.Q4_K_M.gguf",
            hfRepo = "Oxygen-Low/DeepSmolLM3-3B",
            hfFile = "DeepSmolLM3-3B.Q4_K_M.gguf",
            modelScopeUrl = "https://huggingface.co/Oxygen-Low/DeepSmolLM3-3B",
            description = "3B SmolLM3 fine-tune (DeepSeek-V4 reasoning distillation) with dual-mode <think> reasoning, running fully on-device via llama.cpp.",
            supportsReasoning = true,
            downloadState = ModelDownloadState.NOT_DOWNLOADED
        )

        val DEFAULT_MODELS = listOf(DEEP_SMOLLM3)
    }
}
