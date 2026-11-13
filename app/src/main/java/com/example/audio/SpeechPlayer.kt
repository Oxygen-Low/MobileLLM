package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays synthesized speech through the media stack. The samples are written to
 * a short-lived WAV file in the cache directory, which lets MediaPlayer handle
 * routing, focus and completion for us.
 */
class SpeechPlayer(private val context: Context) {

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var player: MediaPlayer? = null
    private val outputFile: File
        get() = File(context.cacheDir, "tts_playback.wav")

    /**
     * Plays [samples] and invokes [onComplete] when playback finishes or fails.
     * Calling this while something is already playing replaces it.
     */
    suspend fun play(samples: FloatArray, sampleRate: Int, onComplete: () -> Unit = {}) {
        stop()
        if (samples.isEmpty() || sampleRate <= 0) return

        val file = outputFile
        withContext(Dispatchers.IO) {
            writeWav(file, samples, sampleRate)
        }

        withContext(Dispatchers.Main) {
            try {
                val mediaPlayer = MediaPlayer()
                player = mediaPlayer
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mediaPlayer.setDataSource(file.absolutePath)
                mediaPlayer.setOnCompletionListener { finished ->
                    finish(finished, file, onComplete)
                }
                mediaPlayer.setOnErrorListener { failed, _, _ ->
                    finish(failed, file, onComplete)
                    true
                }
                mediaPlayer.prepare()
                _isSpeaking.value = true
                mediaPlayer.start()
            } catch (t: Throwable) {
                Log.e(TAG, "Could not play synthesized speech", t)
                stop()
                onComplete()
            }
        }
    }

    fun stop() {
        val active = player
        player = null
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        _isSpeaking.value = false
    }

    private fun finish(mediaPlayer: MediaPlayer, file: File, onComplete: () -> Unit) {
        // A newer playback may have replaced this one; its callbacks own the
        // state and must not be cleared by a stale completion.
        if (player !== mediaPlayer) {
            runCatching { mediaPlayer.release() }
            return
        }
        player = null
        runCatching { mediaPlayer.release() }
        runCatching { file.delete() }
        _isSpeaking.value = false
        onComplete()
    }

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)             // PCM chunk size
            putShort(1)            // PCM
            putShort(1)            // mono
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2)            // block align
            putShort(16)           // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }

        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(header.array())
            val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                val clamped = sample.coerceIn(-1f, 1f)
                buffer.putShort((clamped * Short.MAX_VALUE).toInt().toShort())
            }
            out.write(buffer.array())
        }
    }

    private companion object {
        const val TAG = "SpeechPlayer"
    }
}