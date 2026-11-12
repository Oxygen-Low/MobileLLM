package com.example.model

/**
 * A stored message, reduced to the parts a conversation is rebuilt from.
 */
data class StoredTurn(
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val toolName: String? = null,
    val toolQuery: String? = null
)

/**
 * Rebuilds the conversation of a stored agent turn.
 *
 * The app stores every step of a turn - the reasoning, the tool call and the
 * tool result - so the next turn can be replayed to the model exactly the way
 * the agent saw it. Without this the model would start every follow-up
 * question as if no tool had ever been called, and would repeat work it
 * already paid for.
 */
object AgentTranscript {

    /** Reasoning that is worth replaying (chars). */
    private const val MAX_REPLAYED_REASONING = 800

    /**
     * The assistant turn of a finished reply: its reasoning followed by the
     * answer.
     */
    fun assistantTurn(content: String, reasoning: String?): String {
        val body = content.trim()
        val trace = reasoning?.trim().orEmpty()
        if (trace.isEmpty()) return body
        return "${ThinkTags.OPEN}\n${trace.take(MAX_REPLAYED_REASONING)}\n${ThinkTags.CLOSE}\n$body"
    }

    /**
     * The assistant turn of a tool step: the reasoning that led to the call
     * and the call itself. Null when the step does not name a tool.
     */
    fun toolCallTurn(toolName: String?, argument: String?, reasoning: String?): String? {
        val name = toolName?.trim().orEmpty()
        if (name.isEmpty()) return null
        val call = ToolCallTags.block("$name: ${argument?.trim().orEmpty()}")
        val trace = reasoning?.trim().orEmpty()
        if (trace.isEmpty()) return call
        return "${ThinkTags.OPEN}\n${trace.take(MAX_REPLAYED_REASONING)}\n${ThinkTags.CLOSE}\n$call"
    }

    /**
     * The turns a stored message contributes to the conversation.
     *
     * A tool step becomes two turns - the assistant turn that made the call
     * and the tool turn that answered it - because that is how the model saw
     * the run in the first place.
     */
    fun turnsFor(stored: StoredTurn): List<PromptFormat.Turn> = when (stored.role) {
        "user" -> listOf(PromptFormat.Turn("user", stored.content.trim()))
        "assistant" -> listOf(
            PromptFormat.Turn(
                "assistant",
                assistantTurn(stored.content, stored.reasoning)
            )
        )
        "tool" -> {
            val call = toolCallTurn(stored.toolName, stored.toolQuery, stored.reasoning)
            val turns = mutableListOf<PromptFormat.Turn>()
            if (call != null) turns.add(PromptFormat.Turn("assistant", call))
            turns.add(PromptFormat.Turn("tool", stored.content.trim()))
            turns
        }
        else -> emptyList()
    }
}
