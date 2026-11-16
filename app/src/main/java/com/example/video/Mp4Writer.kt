package com.example.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Muxes the frames and the soundtrack of a generated clip into an MP4.
 *
 * A clip is a real video file with an H.264 picture track and an AAC audio
 * track, because that is what Android can play back with sound. The two tracks
 * are encoded first and then written to the muxer in timestamp order: doing it
 * that way keeps the code free of the "whose sample is next" bookkeeping the
 * streaming encoders need, and a short clip is only a few megabytes.
 *
 * The class talks to Android's media stack, so it has no unit test. The part
 * that can be checked without a device lives in [RgbaToYuv].
 */
object Mp4Writer {

    /** Sample rate of the soundtrack the native engine produces. */
    const val SAMPLE_RATE = 44100

    private const val TAG = "Mp4Writer"
    private const val VIDEO_MIME = "video/avc"
    private const val AUDIO_MIME = "audio/mp4a-latm"
    private const val AUDIO_BIT_RATE = 96_000
    private const val AUDIO_CHUNK_SAMPLES = 2048
    private const val TIMEOUT_US = 10_000L
    private const val MAX_DRAIN_ATTEMPTS = 200

    /**
     * Encodes [frames] (RGBA, `frameCount` frames of `width * height * 4`
     * bytes) and the 16-bit mono PCM in [pcm] into [file].
     *
     * @return true when the file was written. The file is removed when the
     * encoder fails, so a half written clip is never shown as a result.
     */
    fun write(
        file: File,
        width: Int,
        height: Int,
        fps: Int,
        frames: ByteArray,
        frameCount: Int,
        pcm: ByteArray,
        sampleRate: Int = SAMPLE_RATE
    ): Boolean {
        if (frameCount <= 0 || width <= 0 || height <= 0 || fps <= 0) return false
        if (frames.size < width * height * 4 * frameCount) return false

        file.parentFile?.mkdirs()
        val video = runCatching { encodeVideo(width, height, fps, frames, frameCount) }
            .onFailure { Log.e(TAG, "Could not encode the video track", it) }
            .getOrNull() ?: return false
        val audio = if (pcm.size >= 2 && sampleRate > 0) {
            runCatching { encodeAudio(pcm, sampleRate) }
                .onFailure { Log.e(TAG, "Could not encode the audio track", it) }
                .getOrNull()
        } else {
            null
        }

        return runCatching { mux(file, video, audio) }
            .onFailure {
                Log.e(TAG, "Could not mux the clip", it)
                file.delete()
            }
            .getOrDefault(false)
    }

    private fun encodeVideo(
        width: Int,
        height: Int,
        fps: Int,
        frames: ByteArray,
        frameCount: Int
    ): EncodedTrack? {
        val codec = MediaCodec.createEncoderByType(VIDEO_MIME)
        try {
            val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height, fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val samples = mutableListOf<Sample>()
            var outputFormat: MediaFormat? = null
            val frameBytes = width * height * 4
            val frameDurationUs = 1_000_000L / fps

            for (frame in 0 until frameCount) {
                val yuv = RgbaToYuv.toI420(frames, frame * frameBytes, width, height)
                var inputIndex = -1
                while (inputIndex < 0) {
                    inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex < 0) {
                        outputFormat = drain(codec, samples, false).format ?: outputFormat
                    }
                }
                fillVideoInput(codec, inputIndex, yuv, width, height)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    yuv.size,
                    frame * frameDurationUs,
                    0
                )
                outputFormat = drain(codec, samples, false).format ?: outputFormat
            }

            var eosIndex = -1
            while (eosIndex < 0) {
                eosIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (eosIndex < 0) {
                    outputFormat = drain(codec, samples, false).format ?: outputFormat
                }
            }
            codec.queueInputBuffer(
                eosIndex,
                0,
                0,
                frameCount * frameDurationUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            outputFormat = drainUntilEnd(codec, samples) ?: outputFormat

            val resultFormat = outputFormat ?: return null
            return EncodedTrack(resultFormat, samples)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun encodeAudio(pcm: ByteArray, sampleRate: Int): EncodedTrack? {
        val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
        try {
            val format = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_CHUNK_SAMPLES * 2)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val samples = mutableListOf<Sample>()
            var outputFormat: MediaFormat? = null
            val totalSamples = pcm.size / 2
            var sampleIndex = 0

            while (sampleIndex < totalSamples) {
                val count = minOf(AUDIO_CHUNK_SAMPLES, totalSamples - sampleIndex)
                val byteCount = count * 2
                var inputIndex = -1
                while (inputIndex < 0) {
                    inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex < 0) {
                        outputFormat = drain(codec, samples, false).format ?: outputFormat
                    }
                }
                val input = codec.getInputBuffer(inputIndex) ?: break
                input.clear()
                input.put(pcm, sampleIndex * 2, byteCount)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    byteCount,
                    sampleIndex * 1_000_000L / sampleRate,
                    0
                )
                sampleIndex += count
                outputFormat = drain(codec, samples, false).format ?: outputFormat
            }

            var eosIndex = -1
            var attempts = 0
            while (eosIndex < 0 && attempts < MAX_DRAIN_ATTEMPTS) {
                eosIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (eosIndex < 0) {
                    outputFormat = drain(codec, samples, false).format ?: outputFormat
                    attempts++
                }
            }
            if (eosIndex >= 0) {
                codec.queueInputBuffer(
                    eosIndex,
                    0,
                    0,
                    totalSamples * 1_000_000L / sampleRate,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                outputFormat = drainUntilEnd(codec, samples) ?: outputFormat
            }

            val resultFormat = outputFormat ?: return null
            return EncodedTrack(resultFormat, samples)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /**
     * Copies an I420 frame into the encoder's input image. The image can have
     * a row stride and an interleaved chroma layout, so the planes are filled
     * plane by plane instead of assuming a tightly packed buffer.
     */
    private fun fillVideoInput(
        codec: MediaCodec,
        inputIndex: Int,
        i420: ByteArray,
        width: Int,
        height: Int
    ) {
        val image = codec.getInputImage(inputIndex)
        if (image == null) {
            val input = codec.getInputBuffer(inputIndex) ?: return
            input.clear()
            input.put(i420)
            return
        }

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val frameSize = width * height
        val chromaSize = chromaWidth * chromaHeight

        copyPlane(image.planes[0], i420, 0, width, height)
        copyPlane(image.planes[1], i420, frameSize, chromaWidth, chromaHeight)
        copyPlane(image.planes[2], i420, frameSize + chromaSize, chromaWidth, chromaHeight)
    }

    private fun copyPlane(
        plane: android.media.Image.Plane,
        source: ByteArray,
        sourceOffset: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (row in 0 until sourceHeight) {
            val sourceRow = sourceOffset + row * sourceWidth
            if (pixelStride == 1) {
                buffer.position(row * rowStride)
                buffer.put(source, sourceRow, sourceWidth)
            } else {
                for (column in 0 until sourceWidth) {
                    buffer.put(row * rowStride + column * pixelStride, source[sourceRow + column])
                }
            }
        }
    }

    private fun drain(
        codec: MediaCodec,
        samples: MutableList<Sample>,
        endOfStream: Boolean
    ): DrainResult {
        var format: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var attempts = 0
        while (attempts < MAX_DRAIN_ATTEMPTS) {
            attempts++
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return DrainResult(format, false)
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    format = codec.outputFormat
                }

                index >= 0 -> {
                    val ended = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            buffer.get(data)
                            samples.add(Sample(copyInfo(info), data))
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (ended) return DrainResult(format, true)
                }
            }
        }
        return DrainResult(format, false)
    }

    private fun drainUntilEnd(codec: MediaCodec, samples: MutableList<Sample>): MediaFormat? =
        drain(codec, samples, true).format

    private fun mux(file: File, video: EncodedTrack, audio: EncodedTrack?): Boolean {
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoTrack = muxer.addTrack(video.format)
            val audioTrack = audio?.let { muxer.addTrack(it.format) } ?: -1
            muxer.start()

            val all = ArrayList<TrackedSample>(video.samples.size + (audio?.samples?.size ?: 0))
            video.samples.forEach { all.add(TrackedSample(videoTrack, it)) }
            audio?.samples?.forEach { all.add(TrackedSample(audioTrack, it)) }
            all.sortBy { it.sample.info.presentationTimeUs }

            for (tracked in all) {
                val info = tracked.sample.info
                val buffer = ByteBuffer.wrap(tracked.sample.data)
                muxer.writeSampleData(tracked.track, buffer, info)
            }
            return true
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun copyInfo(info: MediaCodec.BufferInfo): MediaCodec.BufferInfo =
        MediaCodec.BufferInfo().apply {
            set(0, info.size, info.presentationTimeUs, info.flags)
        }

    /** A bitrate that keeps a short phone clip small but not blocky. */
    fun bitrateFor(width: Int, height: Int, fps: Int): Int {
        val pixelsPerSecond = width.toLong() * height.toLong() * fps.toLong()
        return (pixelsPerSecond * 3 / 20).coerceIn(400_000L, 4_000_000L).toInt()
    }

    private data class Sample(val info: MediaCodec.BufferInfo, val data: ByteArray)

    private data class EncodedTrack(val format: MediaFormat, val samples: List<Sample>)

    private data class TrackedSample(val track: Int, val sample: Sample)

    private data class DrainResult(val format: MediaFormat?, val endOfStream: Boolean)
}