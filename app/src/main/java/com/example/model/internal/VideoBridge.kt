package com.example.model.internal

/**
 * Thin JNI surface over the on-device video engine (see app/src/main/cpp).
 *
 * Sampling loads an SD 1.5 checkpoint and an AnimateDiff motion module into
 * stable-diffusion.cpp. Both are CPU bound and run on the caller's thread, so
 * [nativeGenerateVideo] must be invoked from a background dispatcher. The
 * soundtrack is not part of this engine: it is synthesized on the Kotlin side
 * with the on-device TTS model once these weights have been released.
 */
object VideoBridge {

    init {
        System.loadLibrary("mobilellm")
    }

    /** Maps the checkpoint and motion module into memory. Returns 0 on failure. */
    external fun nativeLoadVideoModel(path: String, motionPath: String, nThreads: Int): Long

    external fun nativeFreeVideoModel(handle: Long)

    /**
     * Samples the frames of one clip from [prompt].
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
        seed: Int
    ): ByteArray?

    /** Stops a running sampling pass; safe to call from another thread. */
    external fun nativeCancelVideoGeneration(handle: Long)
}
