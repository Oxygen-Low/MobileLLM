package com.example.model

/**
 * One step of an agent turn.
 *
 * A step is a full "the model thought about it → the model asked for a tool →
 * the tool answered" cycle. The steps of a turn are kept next to each other
 * instead of being overwritten by the next round, so the transcript shows what
 * the agent actually did and the next turn can be replayed with that history.
 */
data class AgentStep(
    /** Stable id of the step; the persisted transcript row uses the same id. */
    val id: String,
    /** Name of the tool the model asked for. */
    val toolName: String,
    /** The argument the model passed to the tool. */
    val argument: String,
    /** The reasoning the model produced before it called the tool. */
    val reasoning: String = "",
    /** What the tool answered; null while the call is still running. */
    val result: String? = null,
    /** Link to the source the tool found, if any. */
    val sourceUrl: String? = null,
    /** True while the tool is still working. */
    val isRunning: Boolean = false
)
