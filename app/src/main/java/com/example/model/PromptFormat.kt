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

    /**
     * Resolves the final reasoning mode, honouring explicit `/think` and
     * `/no_think` markers in [system] before falling back to the UI choice.
     */
    fun resolveReasoningMode(
        system: String,
        enableThinking: Boolean,
        reasoningLevel: ReasoningLevel
    ): String = when {
        system.contains("/no_think") -> "/no_think"
        system.contains("/think") -> "/think"
        enableThinking && reasoningLevel.usesThinking -> "/think"
        else -> "/no_think"
    }

    /**
     * Renders [system] and [turns] with the chat template belonging to [style].
     */
    fun render(
        style: PromptStyle,
        system: String,
        turns: List<Turn>,
        enableThinking: Boolean,
        today: String,
        reasoningLevel: ReasoningLevel =
            if (enableThinking) ReasoningLevel.DEFAULT else ReasoningLevel.NONE
    ): String = when (style) {
        PromptStyle.SMOLLM3 -> smolLM3(
            system = system,
            turns = turns,
            enableThinking = enableThinking,
            today = today,
            reasoningLevel = reasoningLevel
        )
        PromptStyle.QWEN3 -> qwen3(
            system = system,
            turns = turns,
            enableThinking = enableThinking,
            reasoningLevel = reasoningLevel
        )
    }

    fun smolLM3(
        system: String,
        turns: List<Turn>,
        enableThinking: Boolean,
        today: String,
        reasoningLevel: ReasoningLevel =
            if (enableThinking) ReasoningLevel.DEFAULT else ReasoningLevel.NONE
    ): String {
        val reasoningMode = resolveReasoningMode(system, enableThinking, reasoningLevel)

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
        } else {
            // Prefill the opening tag so the model is forced to continue inside
            // a reasoning block instead of silently skipping the think phase.
            prompt.append("<think>\n")
        }

        return prompt.toString()
    }

    /**
     * ChatML rendering for the Qwen3 family.
     *
     * Qwen3 ships a `chat_template.jinja` that is plain ChatML plus a soft
     * reasoning switch. Thinking is disabled by prefilling an empty
     * `<think></think>` block rather than by relying on the model's
     * `/no_think` instruction, which the small 0.6B checkpoint does not always
     * follow.
     */
    fun qwen3(
        system: String,
        turns: List<Turn>,
        enableThinking: Boolean,
        reasoningLevel: ReasoningLevel =
            if (enableThinking) ReasoningLevel.DEFAULT else ReasoningLevel.NONE
    ): String {
        val reasoningMode = resolveReasoningMode(system, enableThinking, reasoningLevel)

        val customInstructions = system
            .replace("/no_think", "")
            .replace("/think", "")
            .trim()

        val prompt = StringBuilder(256 + turns.sumOf { it.content.length })
        if (customInstructions.isNotEmpty()) {
            prompt.append("<|im_start|>system\n").append(customInstructions)
            if (reasoningMode == "/think" && reasoningLevel.usesThinking) {
                prompt.append("\n\n").append(reasoningLevel.instruction)
            }
            prompt.append("<|im_end|>\n")
        } else if (reasoningMode == "/no_think") {
            prompt.append("<|im_start|>system\n/no_think<|im_end|>\n")
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
        } else {
            prompt.append("<think>\n")
        }

        return prompt.toString()
    }
}
