package com.example.model

/**
 * Chat prompt rendering.
 *
 * The template below mirrors the `chat_template.jinja` shipped with
 * Oxygen-Low/DeepSmolLM3-3B (a SmolLM3 fine-tune). llama.cpp does not ship a
 * jinja renderer, so this small hand-written version reproduces the same
 * output for the single system/user/assistant message shapes used by the app.
 */
object PromptFormat {

    data class Turn(val role: String, val content: String)

    private const val THINK_SYSTEM =
        "You are a helpful AI assistant named SmolLM, trained by Hugging Face. " +
        "Your role as an assistant involves thoroughly exploring questions through a systematic thinking process " +
        "before providing the final precise and accurate solutions. This requires engaging in a comprehensive cycle " +
        "of analysis, summarizing, exploration, reassessment, reflection, backtracking, and iteration to develop " +
        "well-considered thinking process. Please structure your response into two main sections: Thought and " +
        "Solution using the specified format: <think> Thought section </think> Solution section. In the Thought " +
        "section, detail your reasoning process in steps. Each step should include detailed considerations such as " +
        "analysing questions, summarizing relevant findings, brainstorming new ideas, verifying the accuracy of the " +
        "current steps, refining any errors, and revisiting previous steps. In the Solution section, based on various " +
        "attempts, explorations, and reflections from the Thought section, systematically present the final solution " +
        "that you deem correct. The Solution section should be logical, accurate, and concise and detail necessary " +
        "steps needed to reach the conclusion."

    private const val NO_THINK_SYSTEM =
        "You are a helpful AI assistant named SmolLM, trained by Hugging Face."

    fun smolLM3(
        system: String,
        turns: List<Turn>,
        enableThinking: Boolean,
        today: String,
        reasoningLevel: ReasoningLevel =
            if (enableThinking) ReasoningLevel.DEFAULT else ReasoningLevel.NONE
    ): String {
        var reasoningMode = if (enableThinking) "/think" else "/no_think"
        if (system.contains("/no_think")) {
            reasoningMode = "/no_think"
        } else if (system.contains("/think")) {
            reasoningMode = "/think"
        } else if (!reasoningLevel.usesThinking) {
            reasoningMode = "/no_think"
        }

        val customInstructions = system
            .replace("/no_think", "")
            .replace("/think", "")
            .trimEnd()

        val prompt = StringBuilder(512 + turns.sumOf { it.content.length })
        prompt.append("<|im_start|>system\n")

        if (system.contains("/system_override")) {
            prompt.append(customInstructions.replace("/system_override", "").trimEnd())
            prompt.append("<|im_end|>\n")
        } else {
            prompt.append("## Metadata\n\n")
            prompt.append("Knowledge Cutoff Date: June 2025\n")
            prompt.append("Today Date: ").append(today).append('\n')
            prompt.append("Reasoning Mode: ").append(reasoningMode).append("\n\n")
            prompt.append("## Custom Instructions\n\n")
            when {
                customInstructions.isNotBlank() -> prompt.append(customInstructions).append("\n\n")
                reasoningMode == "/think" -> prompt.append(THINK_SYSTEM).append("\n\n")
                else -> prompt.append(NO_THINK_SYSTEM).append("\n\n")
            }
            if (reasoningMode == "/think") {
                // The model only has a binary native switch (/think vs
                // /no_think), so the requested depth is expressed as a
                // graduated instruction on top of its thinking system prompt.
                val effort = if (reasoningLevel.usesThinking) reasoningLevel else ReasoningLevel.DEFAULT
                prompt.append(effort.instruction).append("\n\n")
            }
        }

        for (turn in turns) {
            if (turn.role == "assistant") {
                prompt.append("<|im_start|>assistant\n")
                    .append(turn.content.trimStart('\n'))
                    .append("<|im_end|>\n")
            } else {
                prompt.append("<|im_start|>user\n")
                    .append(turn.content)
                    .append("<|im_end|>\n")
            }
        }

        prompt.append("<|im_start|>assistant\n")
        if (reasoningMode == "/no_think") {
            prompt.append("<think>\n\n</think>\n")
        }

        return prompt.toString()
    }
}
