package com.example.data

/**
 * Index helpers for the rewind / regenerate actions on a chat transcript.
 *
 * The transcript is stored oldest-first, so "everything after message X" is
 * simply everything with a higher index. Keeping that arithmetic in one small
 * testable place is deliberate: the actions used to compute the index inline
 * with `range.indexOfFirst { ... }`, which returns the *position inside the
 * range* rather than an index into the transcript. That silently rewound to
 * the wrong turn once a chat had more than one exchange in it.
 */
object ChatTranscript {

    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"

    /**
     * Index of the most recent user turn at or before [fromIndex], or -1 when
     * the transcript holds no user turn in that range.
     *
     * The index is read off the range itself - never off the position of a
     * match inside it.
     */
    fun lastUserIndexAtOrBefore(messages: List<ChatMessageEntity>, fromIndex: Int): Int {
        val upper = minOf(fromIndex, messages.lastIndex)
        for (index in upper downTo 0) {
            if (messages[index].role == ROLE_USER) return index
        }
        return -1
    }

    /**
     * Index of the message a rewind should park the transcript on, or -1 when
     * the message is unknown or already the last one (nothing to rewind).
     */
    fun rewindAnchorIndex(messages: List<ChatMessageEntity>, messageId: String): Int {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0 || index == messages.lastIndex) return -1
        return index
    }

    /** Ids of every message that follows [index]; these are the ones a rewind drops. */
    fun idsAfter(messages: List<ChatMessageEntity>, index: Int): List<String> =
        messages.drop(index + 1).map { it.id }

    /**
     * The prompt a reply was produced from, together with the reply itself.
     * Regenerating drops both and replays the prompt.
     */
    data class RegenerationTarget(val promptIndex: Int, val replyIndex: Int)

    /**
     * Works out what a regenerate has to replace, or null when the transcript
     * does not end with a reply that has a prompt behind it.
     */
    fun regenerationTarget(messages: List<ChatMessageEntity>): RegenerationTarget? {
        val replyIndex = messages.lastIndex
        if (replyIndex < 0) return null
        if (messages[replyIndex].role != ROLE_ASSISTANT) return null

        val promptIndex = lastUserIndexAtOrBefore(messages, replyIndex - 1)
        if (promptIndex < 0) return null

        return RegenerationTarget(promptIndex, replyIndex)
    }
}
