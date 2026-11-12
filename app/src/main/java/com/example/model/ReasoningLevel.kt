package com.example.model

/**
 * How much effort the model should put into its hidden reasoning phase.
 *
 * SmolLM3 exposes a native two-state thinking switch via the `/think` and
 * `/no_think` control tokens. [NONE] maps directly onto `/no_think`; the other
 * levels keep the model in think mode and add graduated guidance to the system
 * prompt, while [maxNewTokens] gives deeper levels more room to reason.
 */
enum class ReasoningLevel(
    val label: String,
    val shortLabel: String,
    val description: String,
    val instruction: String,
    /** Maximum number of tokens allowed inside `<think>…</think>`. */
    val reasoningBudget: Int,
    /** Total generation budget: reasoning tokens plus room for the answer. */
    val maxNewTokens: Int
) {
    NONE(
        label = "None",
        shortLabel = "Off",
        description = "No reasoning, answer immediately",
        instruction = "",
        reasoningBudget = 0,
        maxNewTokens = 512
    ),
    LOW(
        label = "Low",
        shortLabel = "Low",
        description = "A few quick reasoning steps",
        instruction = "Keep reasoning brief: use at most a few short steps, then give the final answer. " +
            "Do not overthink simple requests.",
        reasoningBudget = 160,
        maxNewTokens = 672
    ),
    MEDIUM(
        label = "Medium",
        shortLabel = "Med",
        description = "Balanced reasoning depth",
        instruction = "Reason with a moderate amount of detail: use a handful of focused steps, " +
            "verify the key points, then give the final answer.",
        reasoningBudget = 384,
        maxNewTokens = 896
    ),
    HIGH(
        label = "High",
        shortLabel = "High",
        description = "Thorough, multi-step reasoning",
        instruction = "Reason thoroughly before answering. Break the problem into parts, explore " +
            "relevant alternatives, verify assumptions, and check your work.",
        reasoningBudget = 640,
        maxNewTokens = 1152
    ),
    MAX(
        label = "Max",
        shortLabel = "Max",
        description = "Maximum reasoning effort",
        instruction = "Reason as much as possible before answering. Exhaustively explore the " +
            "problem, consider many alternatives, verify and re-verify every step, and do not " +
            "stop until the answer is fully validated.",
        reasoningBudget = 1024,
        maxNewTokens = 1536
    );

    /** True when the model should be allowed to emit a `<think>` block. */
    val usesThinking: Boolean
        get() = this != NONE

    companion object {
        val DEFAULT = MEDIUM
    }
}
