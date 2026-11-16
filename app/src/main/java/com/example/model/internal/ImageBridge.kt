package com.example.model.internal

/**
 * Thin JNI surface over the on-device image engine (see app/src/main/cpp).
 *
 * Loading and sampling are CPU bound and happen on the caller's thread, so
 * [nativeGenerateImage] must be invoked from a background dispatcher. The
 * engine is separate from [LlamaBridge] and shares the same native library.
 */
object ImageBridge {

    init {
        System.loadLibrary("mobilellm")
    }

    /** Maps a diffusion GGUF into memory. Returns 0 when the file is unusable. */
    external fun nativeLoadImageModel(path: String, nThreads: Int): Long

    external fun nativeFreeImageModel(handle: Long)

    /**
     * Samples one picture from [prompt].
     *
     * The hue and saturation anchors come from the prompt (see PromptPalette)
     * rather than from the seed, so the picture paints with the colours that
     * were asked for. The seed only decides the layout of the noise field.
     *
     * @return `width * height * 4` bytes of RGBA, or null on error.
     */
    external fun nativeGenerateImage(
        handle: Long,
        prompt: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Int,
        hueA: Float,
        hueB: Float,
        saturation: Float
    ): ByteArray?

    /** Stops a running sampling pass; safe to call from another thread. */
    external fun nativeCancelImageGeneration(handle: Long)
}
