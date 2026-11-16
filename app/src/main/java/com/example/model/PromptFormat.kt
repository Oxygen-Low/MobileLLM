package com.example.model

/**
 * Chat prompt rendering.
 *
 * Every model family ships its own `chat_template.jinja`. llama.cpp does not
 * ship a jinja renderer, so these small hand-written versions reproduce the
 * same output for the single system/user/assistant message shapes used by the
 * app. The SmolLM3 template below is the one of
 * Oxygen-Low/DeepSmolLM3-3B (a SmolLM3 fine-tune).
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
        PromptStyle.LFM2 -> lfm2(
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
            when (turn.role) {
                "assistant" -> {
                    prompt.append("<|im_start|>assistant\n")
                        .append(turn.content.trimStart('\n'))
                        .append("<|im_end|>\n")
                }
                "tool" -> {
                    prompt.append("<|im_start|>tool\n")
                        .append(turn.content.trim())
                        .append("<|im_end|>\n")
                }
                else -> {
                    prompt.append("<|im_start|>user\n")
                        .append(turn.content)
                        .append("<|im_end|>\n")
                }
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

        val systemBlock = StringBuilder()
        if (customInstructions.isNotEmpty()) {
            systemBlock.append("<|im_start|>system\n").append(customInstructions)
            if (reasoningMode == "/think" && reasoningLevel.usesThinking) {
                systemBlock.append("\n\n").append(reasoningLevel.instruction)
            }
            systemBlock.append("<|im_end|>\n")
        } else if (reasoningMode == "/no_think") {
            systemBlock.append("<|im_start|>system\n/no_think<|im_end|>\n")
        }

        return chatMl(systemBlock.toString(), turns, reasoningMode)
    }

    /**
     * Chat template of the LFM2.5 family (Liquid AI).
     *
     * The upstream `chat_template.jinja` is plain ChatML: every turn is
     * wrapped in `<|im_start|>`/`<|im_end|>` markers and the generation prompt
     * is `<|im_start|>assistant\n`.
     *
     * The Thinking checkpoint reasons before it answers, so the assistant
     * turn is prefilled with `<think>` to open the reasoning block. The
     * reasoning phase is skipped by closing an empty think block instead, the
     * same way the Qwen3 template does it.
     */
    fun lfm2(
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

        val systemBlock = StringBuilder()
        if (customInstructions.isNotEmpty()) {
            systemBlock.append("<|im_start|>system\n").append(customInstructions)
            if (reasoningMode == "/think" && reasoningLevel.usesThinking) {
                systemBlock.append("\n\n").append(reasoningLevel.instruction)
            }
            systemBlock.append("<|im_end|>\n")
        }

        return chatMl(systemBlock.toString(), turns, reasoningMode)
    }

    /**
     * Renders a plain ChatML prompt: the system block, the turns and the
     * assistant turn that is being continued.
     *
     * Qwen3 and LFM2.5 share this layout, only the system block and the way
     * the reasoning phase is switched differ.
     */
    private fun chatMl(
        systemBlock: String,
        turns: List<Turn>,
        reasoningMode: String
    ): String {
        val prompt = StringBuilder(
            systemBlock.length + 256 + turns.sumOf { it.content.length }
        )
        prompt.append(systemBlock)

        for (turn in turns) {
            when (turn.role) {
                "assistant" -> {
                    prompt.append("<|im_start|>assistant\n")
                        .append(turn.content.trimStart('\n'))
                        .append("<|im_end|>\n")
                }
                "tool" -> {
                    prompt.append("<|im_start|>tool\n")
                        .append(turn.content.trim())
                        .append("<|im_end|>\n")
                }
                else -> {
                    prompt.append("<|im_start|>user\n")
                        .append(turn.content)
                        .append("<|im_end|>\n")
                }
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
}
