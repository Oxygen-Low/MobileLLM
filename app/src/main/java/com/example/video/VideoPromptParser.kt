package com.example.video

import com.example.tools.MiniJson

/**
 * What the text model asked the video tool for.
 *
 * The prompt is the only field a caller has to send: the model profile fills
 * in the size, the frame rate, the length and the sampling steps, so a bare
 * description produces a usable clip just like a full argument object does.
 */
data class VideoGenerationRequest(
    val prompt: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val fps: Int,
    val steps: Int,
    val seed: Int?
) {
    /** Length of the clip in seconds. */
    val durationSeconds: Float get() = frameCount.toFloat() / fps.coerceAtLeast(1)
}

/**
 * Reads the argument of the `generate_video` tool.
 *
 * The tool is called either with a JSON object
 * (`{"prompt": "a fox", "seconds": 2}`) or, when the model is terse, with the
 * description on its own. Both have to produce a usable request. The frame
 * count is derived from the length and the frame rate, with a cap so a single
 * request cannot ask for a clip the device cannot hold in memory.
 */
object VideoPromptParser {

    /** The largest edge a request may ask for. */
    const val MAX_SIZE = 512
    const val MIN_SIZE = 96

    /** The motion module was trained with a sixteen-frame context. */
    const val MAX_FRAMES = 16

    /** The longest description that is kept; the rest would not change a clip. */
    const val MAX_PROMPT = 1000

    private fun align(size: Int): Int =
        ((size.coerceIn(MIN_SIZE, MAX_SIZE) + 7) / 8) * 8

    /** The clip lengths and frame rates a phone can afford. */
    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 3
    const val MIN_FPS = 4
    const val MAX_FPS = 24

    fun parse(
        argument: String,
        defaultSize: Int,
        defaultFps: Int,
        defaultSeconds: Int,
        defaultSteps: Int
    ): VideoGenerationRequest {
        val trimmed = argument.trim()
        var prompt = MiniJson.getString(trimmed, "prompt", "description", "query", "text").trim()

        // A model that emitted the description instead of an argument object.
        if (prompt.isEmpty() && !trimmed.startsWith("{")) {
            prompt = trimmed.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        }

        // Latent diffusion works on a downsampled grid, so keep the edges on
        // an eight-pixel boundary and inside the motion module's resolution.
        val width = align(MiniJson.getInt(trimmed, "width", default = defaultSize))
        val height = align(MiniJson.getInt(trimmed, "height", default = defaultSize))
        val seconds = MiniJson.getInt(trimmed, "seconds", "duration", "length", default = defaultSeconds)
            .coerceIn(MIN_SECONDS, MAX_SECONDS)
        val fps = MiniJson.getInt(trimmed, "fps", "frame_rate", default = defaultFps)
            .coerceIn(MIN_FPS, MAX_FPS)
        val steps = MiniJson.getInt(trimmed, "steps", default = defaultSteps)
            .coerceIn(1, 50)
        val rawSeed = MiniJson.getInt(trimmed, "seed", default = -1)

        return VideoGenerationRequest(
            prompt = prompt.take(MAX_PROMPT),
            width = width,
            height = height,
            frameCount = (seconds * fps).coerceAtMost(MAX_FRAMES),
            fps = fps,
            steps = steps,
            seed = rawSeed.takeIf { it >= 0 }
        )
    }
}