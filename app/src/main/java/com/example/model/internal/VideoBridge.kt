package com.example.model.internal

/**
 * Thin JNI surface over the on-device video engine (see app/src/main/cpp).
 *
 * Loading and sampling are CPU bound and happen on the caller's thread, so
 * [nativeGenerateVideo] and [nativeGenerateAudio] must be invoked from a
 * background dispatcher. The engine is separate from [LlamaBridge] and
 * [ImageBridge] and shares the same native library.
 *
 * The frames and the soundtrack are produced by two calls of the same engine,
 * seeded the same way, so the sound belongs to the clip that was just drawn.
 */
object VideoBridge {

    init {
        System.loadLibrary("mobilellm")
    }

    /** Maps a video GGUF into memory. Returns 0 when the file is unusable. */
    external fun nativeLoadVideoModel(path: String, nThreads: Int): Long

    external fun nativeFreeVideoModel(handle: Long)

    /**
     * Samples the frames of one clip from [prompt].
     *
     * The hue and saturation anchors come from the prompt (see PromptPalette),
     * so the whole clip keeps the colours that were asked for while the noise
     * field slides over time. The seed only decides the layout of that field.
     *
     * @return `frameCount * width * height * 4` bytes of RGBA, or null on error.
     */
    external fun nativeGenerateVideo(
        handle: Long,
        prompt: String,
        width: Int,
        height: Int,
        frameCount: Int,
        steps: Int,
        seed: Int,
        hueA: Float,
        hueB: Float,
        saturation: Float
    ): ByteArray?

    /**
     * Synthesizes the soundtrack of the same clip.
     *
     * @return 16-bit little-endian mono PCM, or null on error.
     */
    external fun nativeGenerateAudio(
        handle: Long,
        prompt: String,
        frameCount: Int,
        fps: Int,
        sampleRate: Int,
        seed: Int
    ): ByteArray?

    /** Stops a running sampling pass; safe to call from another thread. */
    external fun nativeCancelVideoGeneration(handle: Long)
}