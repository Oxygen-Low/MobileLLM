package com.example.image

import com.example.tools.MiniJson

/**
 * What the text model asked the image tool for.
 *
 * Every field except the prompt has a sensible default, because small models
 * rarely fill in a complete argument object: a bare description has to work
 * just as well as a full JSON one.
 */
data class ImageGenerationRequest(
    val prompt: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val seed: Int?
)

/**
 * Reads the argument of the `generate_image` tool.
 *
 * The tool is called either with a JSON object
 * (`{"prompt": "a fox", "steps": 6}`) or, when the model is terse, with the
 * description on its own. Both have to produce a usable request.
 */
object ImagePromptParser {

    /** The largest edge a request may ask for. */
    const val MAX_SIZE = 1024
    const val MIN_SIZE = 64

    /** The longest description that is kept; the rest would not change a picture. */
    const val MAX_PROMPT = 1000

    fun parse(argument: String, defaultSize: Int, defaultSteps: Int): ImageGenerationRequest {
        val trimmed = argument.trim()
        var prompt = MiniJson.getString(trimmed, "prompt", "description", "query", "text").trim()

        // A model that emitted the description instead of an argument object.
        if (prompt.isEmpty() && !trimmed.startsWith("{")) {
            prompt = trimmed.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        }

        val width = MiniJson.getInt(trimmed, "width", default = defaultSize)
            .coerceIn(MIN_SIZE, MAX_SIZE)
        val height = MiniJson.getInt(trimmed, "height", default = defaultSize)
            .coerceIn(MIN_SIZE, MAX_SIZE)
        val steps = MiniJson.getInt(trimmed, "steps", default = defaultSteps)
            .coerceIn(1, 50)
        val rawSeed = MiniJson.getInt(trimmed, "seed", default = -1)

        return ImageGenerationRequest(
            prompt = prompt.take(MAX_PROMPT),
            width = width,
            height = height,
            steps = steps,
            seed = rawSeed.takeIf { it >= 0 }
        )
    }
}
