package com.example.model

import java.io.File

/**
 * One on-device video generation setup.
 *
 * A full LTX video checkpoint needs a separate 12B text encoder and two VAEs
 * on top of its eight-gigabyte transformer, which cannot fit on a phone next
 * to the chat model. AnimateDiff gets real, prompt-driven motion from an SD 1.5
 * checkpoint plus a much smaller motion module, so it is what this app ships.
 * The audio is not part of the checkpoint: it is narrated by the on-device TTS
 * model after the video weights have been released.
 */
data class VideoModelProfile(
    val id: String,
    val name: String,
    val shortName: String,
    val quantization: String,
    val parameters: String,
    /** The square edge the motion module was trained for, in pixels. */
    val resolution: Int,
    /** Frames per second the clip is rendered and played at. */
    val frameRate: Int,
    /** Length of a clip when the caller does not ask for a specific one. */
    val durationSeconds: Int,
    /** Sampling steps used when the caller does not ask for a specific count. */
    val steps: Int,
    val memoryMb: Int,
    /** True when the clip gets a narration track from the TTS model. */
    val hasAudio: Boolean,
    val description: String,
    /** Repository and file of the SD 1.5 checkpoint the motion module drives. */
    val hfRepo: String,
    val hfFile: String,
    val downloadSizeMb: Int,
    /** Hugging Face repository and file of the AnimateDiff motion module. */
    val motionRepo: String,
    val motionFile: String,
    val motionSizeMb: Int
) {
    /** The file the checkpoint weights are stored in under [root]. */
    fun fileIn(root: File): File = File(root, "$id.gguf")

    /** The file the motion module is stored in under [root]. */
    fun motionFileIn(root: File): File = File(root, "$id.motion.safetensors")

    /** Both pieces are needed before a clip can be sampled. */
    fun isReady(root: File): Boolean =
        fileIn(root).length() >= MIN_MODEL_BYTES &&
            motionFileIn(root).length() >= MIN_MOTION_BYTES

    companion object {
        private const val MIN_MODEL_BYTES = 1024L * 1024L
        private const val MIN_MOTION_BYTES = 512L * 1024L

        val ANIMATEDIFF_SD15 = VideoModelProfile(
            id = "animatediff-sd15",
            name = "AnimateDiff SD 1.5",
            shortName = "AnimateDiff",
            quantization = "Q4_0",
            parameters = "SD 1.5 + motion v3",
            resolution = 512,
            frameRate = 8,
            durationSeconds = 2,
            steps = 20,
            memoryMb = 3500,
            hasAudio = true,
            description = "An SD 1.5 checkpoint driven by an AnimateDiff motion module. It draws short clips with real, prompt-driven motion and adds a narration track with the on-device TTS model.",
            hfRepo = "second-state/stable-diffusion-v1-5-GGUF",
            hfFile = "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf",
            downloadSizeMb = 1494,
            motionRepo = "conrevo/AnimateDiff-A1111",
            motionFile = "motion_module/mm_sd15_v3.safetensors",
            motionSizeMb = 836
        )

        val MODELS = listOf(ANIMATEDIFF_SD15)

        /** The model that is picked before the user picks one. */
        val DEFAULT_VIDEO_MODEL = ANIMATEDIFF_SD15
    }
}
