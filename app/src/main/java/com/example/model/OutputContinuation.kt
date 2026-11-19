package com.example.model

/**
 * Detects a reply that stopped mid-thought and joins its continuation.
 *
 * A generation can run out of its token budget in the middle of a sentence. The
 * model is then asked to continue with the unfinished text in place, and the
 * result is stitched back onto the original. The detection is deliberately
 * conservative: a finished reply must never be extended a second time.
 */
object OutputContinuation {

    const val CONTINUE_INSTRUCTION =
        "Your previous reply was cut off before you finished. Continue exactly where you stopped. " +
            "Do not repeat what you already wrote, do not start over, and do not add an introduction."

    private const val MIN_LENGTH = 80
    private const val MIN_OVERLAP = 3
    private const val MAX_OVERLAP = 240

    /**
     * True when the text ends in the middle of a word or sentence.
     *
     * A trailing full stop, question mark, closing bracket or code fence means
     * the answer is complete; an alphanumeric ending after a long answer means
     * it was cut short. Short answers are left alone: they are often complete
     * one-line replies.
     */
    fun looksCutOff(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.length < MIN_LENGTH) return false
        val last = trimmed.last()
        if (!last.isLetterOrDigit() && last != ',' && last != ';') return false
        val lastLine = trimmed.substringAfterLast('\n').trim()
        // A heading or a short final line typically ends without punctuation
        // but is still a complete thought.
        return lastLine.length >= 12
    }

    /**
     * Appends [continuation] to [original], dropping text the model repeated
     * across the boundary. The overlap is searched from the longest match down
     * so a repeated sentence is removed as a whole.
     */
    fun merge(original: String, continuation: String): String {
        val head = original.trimEnd()
        // Keep whitespace at the start of the continuation: it is the
        // separator between the two halves when the model resumes a sentence.
        var tail = continuation
        if (head.isEmpty()) return tail
        if (tail.isEmpty()) return head

        val limit = minOf(MAX_OVERLAP, head.length, tail.length)
        for (length in limit downTo MIN_OVERLAP) {
            if (head.endsWith(tail.take(length))) {
                tail = tail.substring(length)
                break
            }
        }
        return if (tail.isEmpty()) head else head + tail
    }

    /** The part of [merged] that was not in [original], for incremental display. */
    fun appended(original: String, merged: String): String {
        val head = original.trimEnd()
        return if (merged.startsWith(head)) merged.substring(head.length) else merged
    }
}
