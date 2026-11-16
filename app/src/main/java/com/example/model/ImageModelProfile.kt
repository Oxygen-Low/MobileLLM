package com.example.model

import java.io.File

/**
 * One on-device image generation model.
 *
 * The weights are an all-in-one GGUF of a diffusion checkpoint, so a model is
 * a single file just like a chat model and lives in the same `models` folder.
 * That means the same download, resume, delete and "is it ready" logic can be
 * reused for both kinds of weights.
 *
 * A model carries the sampling defaults its checkpoint was trained for, so the
 * tool can ask for a picture without knowing anything about the sampler.
 */
data class ImageModelProfile(
    val id: String,
    val name: String,
    val shortName: String,
    val quantization: String,
    val parameters: String,
    /** The square edge the checkpoint was trained for, in pixels. */
    val resolution: Int,
    /** Sampling steps used when the caller does not ask for a specific count. */
    val steps: Int,
    val memoryMb: Int,
    val description: String,
    /** ModelScope repository slug, used as the first download source. */
    val modelScopeRepo: String,
    val modelScopeFile: String,
    val modelScopeUrl: String,
    /** Hugging Face repository slug, used as a fallback download source. */
    val hfRepo: String,
    val hfFile: String,
    val downloadSizeMb: Int
) {
    /** The file the weights are stored in under [root]. */
    fun fileIn(root: File): File = File(root, "$id.gguf")

    /**
     * True when the weights are on the device.
     *
     * A file that is still growing (a `.part` in place) is not ready, and a
     * suspiciously small file is treated as a broken download.
     */
    fun isReady(root: File): Boolean {
        val file = fileIn(root)
        return file.isFile && file.length() >= MIN_SIZE_BYTES
    }

    companion object {
        private const val MIN_SIZE_BYTES = 1024L * 1024L

        /**
         * The fast checkpoint: a distilled turbo model that reaches a usable
         * picture in a handful of steps, which is what a phone can afford.
         */
        val SD_TURBO = ImageModelProfile(
            id = "sd-turbo",
            name = "SD-Turbo",
            shortName = "SD-Turbo",
            quantization = "Q4_0",
            parameters = "SD 2.1",
            resolution = 512,
            steps = 4,
            memoryMb = 1600,
            downloadSizeMb = 1700,
            modelScopeRepo = "second-state/SD-Turbo-GGUF",
            modelScopeFile = "sd-turbo-Q4_0.gguf",
            hfRepo = "second-state/SD-Turbo-GGUF",
            hfFile = "sd-turbo-Q4_0.gguf",
            modelScopeUrl = "https://huggingface.co/second-state/SD-Turbo-GGUF",
            description = "A distilled turbo diffusion model that needs only a few sampling steps, the quickest way to a picture on a phone."
        )

        /**
         * The quality checkpoint: the classic 1.5 base, slower but steadier
         * with detailed prompts.
         */
        val SD_V1_5 = ImageModelProfile(
            id = "sd-v1-5",
            name = "Stable Diffusion 1.5",
            shortName = "SD 1.5",
            quantization = "Q4_0",
            parameters = "SD 1.5",
            resolution = 512,
            steps = 20,
            memoryMb = 2000,
            downloadSizeMb = 2100,
            modelScopeRepo = "second-state/stable-diffusion-v1-5-GGUF",
            modelScopeFile = "stable-diffusion-v1-5-Q4_0.gguf",
            hfRepo = "second-state/stable-diffusion-v1-5-GGUF",
            hfFile = "stable-diffusion-v1-5-Q4_0.gguf",
            modelScopeUrl = "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF",
            description = "The Stable Diffusion 1.5 base checkpoint. Slower than the turbo one but more faithful with long descriptions."
        )

        val MODELS = listOf(SD_TURBO, SD_V1_5)

        /** The model that is picked before the user picks one. */
        val DEFAULT_IMAGE_MODEL = SD_TURBO
    }
}
