package com.example.model.internal

/**
 * Thin JNI surface over the vendored llama.cpp build (see app/src/main/cpp).
 *
 * All inference is CPU based and happens on the caller's thread. [nativeGenerate]
 * blocks until generation finishes, so it must always be invoked from a
 * background dispatcher.
 */
object LlamaBridge {

    init {
        System.loadLibrary("mobilellm")
    }

    /** Receives raw UTF-8 bytes for every generated token. Return false to stop. */
    fun interface TokenCallback {
        fun onToken(bytes: ByteArray): Boolean
    }

    external fun nativeBackendInit()

    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int): Long

    external fun nativeFree(handle: Long)

    external fun nativeCancel(handle: Long)

    external fun nativeModelDescription(handle: Long): String

    external fun nativeContextSize(handle: Long): Int

    external fun nativeCountTokens(handle: Long, text: String): Int

    /**
     * @return number of generated tokens, -1 on error or -2 when cancelled.
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        seed: Int,
        maxReasoningTokens: Int,
        callback: TokenCallback
    ): Int
}
