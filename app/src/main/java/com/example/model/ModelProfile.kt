package com.example.model

enum class ModelDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

/** Chat template family used to render prompts for a model. */
enum class PromptStyle {
    SMOLLM3,
    QWEN3,
    LFM2
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
    /** Chat template family used by this model. */
    val promptStyle: PromptStyle = PromptStyle.SMOLLM3,
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
         * The default model shipped by the app.
         *
         * Qwen3's official 0.6B GGUF release (Q8_0, the only quant shipped in
         * the upstream repo). Small enough to stay comfortably in RAM on most
         * phones while still supporting Qwen's hybrid thinking mode.
         */
        val QWEN3_0_6B = ModelProfile(
            id = "qwen3-0.6b",
            name = "Qwen3-0.6B",
            shortName = "Qwen3",
            format = ModelFormat.GGUF,
            quantization = "Q8_0",
            parameters = "0.6B",
            contextLength = 32768,
            memoryMb = 900,
            downloadSizeMb = 610,
            modelScopeRepo = "Qwen/Qwen3-0.6B-GGUF",
            modelScopeFile = "Qwen3-0.6B-Q8_0.gguf",
            hfRepo = "Qwen/Qwen3-0.6B-GGUF",
            hfFile = "Qwen3-0.6B-Q8_0.gguf",
            modelScopeUrl = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF",
            description = "Qwen3 0.6B (Q8_0) with switchable thinking mode, running fully on-device via llama.cpp.",
            supportsReasoning = true,
            promptStyle = PromptStyle.QWEN3,
            downloadState = ModelDownloadState.NOT_DOWNLOADED
        )

        /**
         * An alternative larger model shipped by the app.
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

        /**
         * Liquid AI's LFM2.5-1.2B-Thinking, the reasoning model of the family.
         *
         * The checkpoint mixes convolution and attention layers, so it needs
         * little memory and decodes fast on a phone. Q6_K is the smallest
         * quantisation that keeps the quality of the 1.2B weights intact: the
         * file is 918 MB and most of the context lives in the cheap
         * convolution state instead of the KV cache.
         *
         * Unlike the plain instruction tuned checkpoints it was trained with
         * a reasoning phase, so it thinks before it answers and the thinking
         * can be folded away in the UI.
         */
        val LFM2_5_1_2B_THINKING = ModelProfile(
            id = "lfm2.5-1.2b-thinking",
            name = "LFM2.5-1.2B-Thinking",
            shortName = "LFM2.5",
            format = ModelFormat.GGUF,
            quantization = "Q6_K",
            parameters = "1.2B",
            contextLength = 32768,
            memoryMb = 1350,
            downloadSizeMb = 918,
            modelScopeRepo = "LiquidAI/LFM2.5-1.2B-Thinking-GGUF",
            modelScopeFile = "LFM2.5-1.2B-Thinking-Q6_K.gguf",
            hfRepo = "LiquidAI/LFM2.5-1.2B-Thinking-GGUF",
            hfFile = "LFM2.5-1.2B-Thinking-Q6_K.gguf",
            modelScopeUrl = "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF",
            description = "Liquid AI's LFM2.5-1.2B-Thinking, a hybrid convolution and attention model that reasons before it answers.",
            supportsReasoning = true,
            promptStyle = PromptStyle.LFM2,
            downloadState = ModelDownloadState.NOT_DOWNLOADED
        )

        val DEFAULT_MODELS = listOf(QWEN3_0_6B, DEEP_SMOLLM3, LFM2_5_1_2B_THINKING)
    }
}
