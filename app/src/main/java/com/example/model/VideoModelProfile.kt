package com.example.model

import java.io.File

/**
 * One on-device video generation model.
 *
 * The weights are a single GGUF of a video checkpoint, so a model is a file
 * just like a chat or image model and lives in the same `models` folder. That
 * means the same download, resume, delete and "is it ready" logic can be
 * reused for every kind of weights.
 *
 * Every model in this catalogue generates a soundtrack together with the
 * picture. The audio and the frames come out of the same sampling pass, which
 * is what makes the sound follow the clip instead of being tagged on later.
 */
data class VideoModelProfile(
    val id: String,
    val name: String,
    val shortName: String,
    val quantization: String,
    val parameters: String,
    /** The square edge the checkpoint is sampled at, in pixels. */
    val resolution: Int,
    /** Frames per second the clip is rendered and played at. */
    val frameRate: Int,
    /** Length of a clip when the caller does not ask for a specific one. */
    val durationSeconds: Int,
    /** Sampling steps used when the caller does not ask for a specific count. */
    val steps: Int,
    val memoryMb: Int,
    /** True when the checkpoint produces a soundtrack together with the frames. */
    val hasAudio: Boolean,
    val description: String,
    /**
     * ModelScope repository slug and file, used as the first download source.
     *
     * They are null for a checkpoint that ModelScope does not mirror: the
     * installer then goes straight to Hugging Face instead of asking a mirror
     * that cannot serve the file.
     */
    val modelScopeRepo: String?,
    val modelScopeFile: String?,
    val modelScopeUrl: String?,
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
         * The fast checkpoint: a distilled LTX-2.5 that reaches a usable clip
         * in a handful of steps. It is the quickest way to see moving pictures
         * with sound on a phone.
         */
        val LTX_2_5_DISTILLED = VideoModelProfile(
            id = "ltx-2.5-distilled-q2",
            name = "LTX-2.5 Distilled",
            shortName = "LTX-2.5 D",
            quantization = "Q2_K",
            parameters = "LTX-2.5 22B",
            resolution = 384,
            frameRate = 16,
            durationSeconds = 2,
            steps = 8,
            memoryMb = 7600,
            hasAudio = true,
            downloadSizeMb = 8426,
            modelScopeRepo = null,
            modelScopeFile = null,
            modelScopeUrl = "https://huggingface.co/realrebelai/LTX-2.5_GGUFs",
            hfRepo = "realrebelai/LTX-2.5_GGUFs",
            hfFile = "LTX-2.5-Distilled-Q2_K.gguf",
            description = "A distilled LTX-2.5. It draws short clips and their synchronized soundtrack in a handful of steps, the fastest way to a video on a flagship phone."
        )

        /**
         * The quality checkpoint: the full LTX-2.5 weights. Slower, but the
         * motion and the sound follow a detailed description more closely.
         */
        val LTX_2_5 = VideoModelProfile(
            id = "ltx-2.5-q2",
            name = "LTX-2.5 22B",
            shortName = "LTX-2.5",
            quantization = "Q2_K",
            parameters = "LTX-2.5 22B",
            resolution = 384,
            frameRate = 16,
            durationSeconds = 2,
            steps = 20,
            memoryMb = 8200,
            hasAudio = true,
            downloadSizeMb = 8067,
            modelScopeRepo = null,
            modelScopeFile = null,
            modelScopeUrl = "https://huggingface.co/unsloth/LTX-2.5-GGUF",
            hfRepo = "unsloth/LTX-2.5-GGUF",
            hfFile = "ltx-2.5-22b-dev-Q2_K.gguf",
            description = "The full LTX-2.5 checkpoint. Richer motion and a more detailed soundtrack than the distilled one, at more sampling steps."
        )

        /**
         * The lighter generation: LTX-2 is a little smaller on disk. It still
         * renders the picture and the audio in the same pass.
         */
        val LTX_2 = VideoModelProfile(
            id = "ltx-2-q2",
            name = "LTX-2 19B",
            shortName = "LTX-2",
            quantization = "Q2_K",
            parameters = "LTX-2 19B",
            resolution = 384,
            frameRate = 16,
            durationSeconds = 2,
            steps = 16,
            memoryMb = 7000,
            hasAudio = true,
            downloadSizeMb = 7722,
            modelScopeRepo = null,
            modelScopeFile = null,
            modelScopeUrl = "https://huggingface.co/unsloth/LTX-2-GGUF",
            hfRepo = "unsloth/LTX-2-GGUF",
            hfFile = "ltx-2-19b-dev-Q2_K.gguf",
            description = "The previous LTX generation. Slightly smaller on disk than LTX-2.5 and still generates a clip together with its soundtrack."
        )

        val MODELS = listOf(LTX_2_5_DISTILLED, LTX_2_5, LTX_2)

        /** The model that is picked before the user picks one. */
        val DEFAULT_VIDEO_MODEL = LTX_2_5_DISTILLED
    }
}