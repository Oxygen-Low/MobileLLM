package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Records 16 kHz mono PCM, which is the input format the speech recognizer
 * expects. Recording is push-to-talk: [start] while the user holds the mic
 * button, [stop] returns the captured samples.
 */
class SpeechRecorder(private val context: Context) {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var recorder: AudioRecord? = null
    private var readJob: Job? = null
    private val buffer = ByteArrayOutputStream()

    fun start(scope: CoroutineScope) {
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission is required for speech to text")
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBuffer, SAMPLE_RATE * 2) // ~0.5 s

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Could not open the microphone")
        }

        buffer.reset()
        recorder = record
        record.startRecording()
        _isRecording.value = true

        readJob = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(bufferSize.coerceAtLeast(4096))
            try {
                while (isActive) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        synchronized(buffer) {
                            // Cap the clip so a forgotten recording cannot grow
                            // without bound; keep draining the device buffer.
                            if (buffer.size() < MAX_BYTES) {
                                buffer.write(chunk, 0, minOf(read, MAX_BYTES - buffer.size()))
                            }
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Microphone read failed", t)
            }
        }
    }

    /** Stops recording and returns the captured audio, normalized to [-1, 1]. */
    suspend fun stop(): FloatArray = withContext(Dispatchers.IO) {
        val active = recorder ?: return@withContext FloatArray(0)
        recorder = null

        runCatching { active.stop() }
        readJob?.join()
        readJob = null
        runCatching { active.release() }
        _isRecording.value = false

        val bytes = synchronized(buffer) {
            buffer.toByteArray().also { buffer.reset() }
        }
        pcm16ToFloat(bytes)
    }

    /** Discards the current recording without producing samples. */
    suspend fun cancel() = withContext(Dispatchers.IO) {
        val active = recorder
        recorder = null
        runCatching { active?.stop() }
        readJob?.cancel()
        readJob = null
        runCatching { active?.release() }
        synchronized(buffer) { buffer.reset() }
        _isRecording.value = false
        Unit
    }

    private fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        if (bytes.size < 2) return FloatArray(0)
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        return FloatArray(shorts.size) { shorts[it] / 32768f }
    }

    private companion object {
        const val TAG = "SpeechRecorder"
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 120
        const val MAX_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS
    }
}