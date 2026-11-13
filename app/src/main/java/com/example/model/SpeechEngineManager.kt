package com.example.model

import android.content.Context
import android.util.Log
import com.example.data.SpeechPreferences
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Lifecycle of one speech feature. */
enum class SpeechEngineState {
    /** The user turned the feature off. */
    DISABLED,

    /** Enabled, but the model still has to be downloaded. */
    NOT_DOWNLOADED,

    /** Weights are being transferred. */
    DOWNLOADING,

    /** Weights are being mapped into memory. */
    LOADING,

    /** Resident and usable. */
    READY,

    /** Enabled and downloaded, released while idle; reloads on next use. */
    UNLOADED,

    ERROR
}

data class SpeechAudio(
    val samples: FloatArray,
    val sampleRate: Int
)

/**
 * Owns the small speech models and loads them lazily.
 *
 * Nothing is allocated until the matching toggle is on: enabling a feature
 * downloads the model if needed and loads it, disabling it releases the
 * weights. The same [ModelDownloadManager] that fetches the GGUF chat models
 * stores the speech archives, and the view model calls [restoreEnabled] and
 * [releaseEngines] from the same app-start/app-stop/idle hooks that autoload
 * the chat model.
 */
class SpeechEngineManager(
    context: Context,
    private val downloads: ModelDownloadManager
) {
    private val preferences = SpeechPreferences(context.applicationContext)

    private val _sttEnabled = MutableStateFlow(preferences.speechToTextEnabled)
    val sttEnabled: StateFlow<Boolean> = _sttEnabled.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(preferences.textToSpeechEnabled)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    private val _sttState = MutableStateFlow(stateFromDisk(preferences.speechToTextEnabled, SpeechModelProfile.WHISPER_TINY))
    val sttState: StateFlow<SpeechEngineState> = _sttState.asStateFlow()

    private val _ttsState = MutableStateFlow(stateFromDisk(preferences.textToSpeechEnabled, SpeechModelProfile.PIPER_AMY_LOW))
    val ttsState: StateFlow<SpeechEngineState> = _ttsState.asStateFlow()

    private val _sttError = MutableStateFlow<String?>(null)
    val sttError: StateFlow<String?> = _sttError.asStateFlow()

    private val _ttsError = MutableStateFlow<String?>(null)
    val ttsError: StateFlow<String?> = _ttsError.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing: StateFlow<Boolean> = _isSynthesizing.asStateFlow()

    private var recognizer: OfflineRecognizer? = null
    private var tts: OfflineTts? = null

    private val recognizerMutex = Mutex()
    private val ttsMutex = Mutex()
    private val sttDownloadMutex = Mutex()
    private val ttsDownloadMutex = Mutex()

    val sttModel: SpeechModelProfile = SpeechModelProfile.WHISPER_TINY
    val ttsModel: SpeechModelProfile = SpeechModelProfile.PIPER_AMY_LOW

    /**
     * Reconciles the state with what is actually on disk. Called once when the
     * view model is created, after the download folder has been scanned.
     */
    fun refresh() {
        if (recognizer == null) {
            _sttState.value = stateFromDisk(sttEnabled.value, sttModel)
        }
        if (tts == null) {
            _ttsState.value = stateFromDisk(ttsEnabled.value, ttsModel)
        }
    }

    /**
     * Autoloads the engines that were enabled in a previous session. Mirrors
     * the chat model's autoload on app start; if the weights are missing the
     * download is resumed.
     */
    suspend fun restoreEnabled() {
        if (_sttEnabled.value) {
            ensureSttReady()
        } else {
            _sttState.value = SpeechEngineState.DISABLED
        }
        if (_ttsEnabled.value) {
            ensureTtsReady()
        } else {
            _ttsState.value = SpeechEngineState.DISABLED
        }
    }

    suspend fun setSttEnabled(enabled: Boolean) {
        preferences.speechToTextEnabled = enabled
        _sttEnabled.value = enabled
        if (enabled) {
            _sttError.value = null
            ensureSttReady()
        } else {
            unloadStt()
        }
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        preferences.textToSpeechEnabled = enabled
        _ttsEnabled.value = enabled
        if (enabled) {
            _ttsError.value = null
            ensureTtsReady()
        } else {
            unloadTts()
        }
    }

    /** Releases the weights without changing the user's toggle. */
    suspend fun releaseEngines() {
        unloadStt()
        unloadTts()
    }

    /** Clears load/download errors so the UI banner can be dismissed. */
    fun clearErrors() {
        _sttError.value = null
        _ttsError.value = null
    }

    /**
     * Loads the STT model if it is not resident yet, downloading it first when
     * necessary. Safe to call from the mic button: a no-op while disabled.
     */
    suspend fun ensureSttReady() {
        if (!_sttEnabled.value) return
        if (recognizer != null) {
            _sttState.value = SpeechEngineState.READY
            return
        }

        sttDownloadMutex.withLock {
            if (!_sttEnabled.value) return
            if (!sttModel.isReady(downloads.speechDir)) {
                _sttState.value = SpeechEngineState.DOWNLOADING
                downloads.startSpeechDownload(sttModel)
            }
        }

        if (!_sttEnabled.value) return
        if (!sttModel.isReady(downloads.speechDir)) {
            _sttState.value = SpeechEngineState.ERROR
            _sttError.value = downloads.downloadStates.value[sttModel.id]?.error
                ?: "Could not prepare ${sttModel.name}"
            return
        }

        recognizerMutex.withLock {
            if (!_sttEnabled.value || recognizer != null) return
            _sttState.value = SpeechEngineState.LOADING
            try {
                withContext(Dispatchers.IO) {
                    val dir = downloads.getSpeechModelDir(sttModel.id)
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                    val config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder = File(dir, "tiny.en-encoder.int8.onnx").absolutePath,
                                decoder = File(dir, "tiny.en-decoder.int8.onnx").absolutePath,
                                language = "en"
                            ),
                            tokens = File(dir, "tiny.en-tokens.txt").absolutePath,
                            numThreads = threads,
                            modelType = "whisper"
                        ),
                        decodingMethod = "greedy_search"
                    )
                    recognizer = OfflineRecognizer(config = config)
                }
                _sttState.value = SpeechEngineState.READY
                Log.i(TAG, "${sttModel.name} loaded")
            } catch (t: Throwable) {
                Log.e(TAG, "Could not load the speech recognizer", t)
                runCatching { recognizer?.release() }
                recognizer = null
                _sttState.value = SpeechEngineState.ERROR
                _sttError.value = t.message ?: "Could not load ${sttModel.name}"
            }
        }
    }

    /** Loads the TTS model if it is not resident yet. */
    suspend fun ensureTtsReady() {
        if (!_ttsEnabled.value) return
        if (tts != null) {
            _ttsState.value = SpeechEngineState.READY
            return
        }

        ttsDownloadMutex.withLock {
            if (!_ttsEnabled.value) return
            if (!ttsModel.isReady(downloads.speechDir)) {
                _ttsState.value = SpeechEngineState.DOWNLOADING
                downloads.startSpeechDownload(ttsModel)
            }
        }

        if (!_ttsEnabled.value) return
        if (!ttsModel.isReady(downloads.speechDir)) {
            _ttsState.value = SpeechEngineState.ERROR
            _ttsError.value = downloads.downloadStates.value[ttsModel.id]?.error
                ?: "Could not prepare ${ttsModel.name}"
            return
        }

        ttsMutex.withLock {
            if (!_ttsEnabled.value || tts != null) return
            _ttsState.value = SpeechEngineState.LOADING
            try {
                withContext(Dispatchers.IO) {
                    val dir = downloads.getSpeechModelDir(ttsModel.id)
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                    val config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = File(dir, "en_US-amy-low.onnx").absolutePath,
                                tokens = File(dir, "tokens.txt").absolutePath,
                                dataDir = File(dir, "espeak-ng-data").absolutePath
                            ),
                            numThreads = threads
                        )
                    )
                    tts = OfflineTts(config = config)
                }
                _ttsState.value = SpeechEngineState.READY
                Log.i(TAG, "${ttsModel.name} loaded")
            } catch (t: Throwable) {
                Log.e(TAG, "Could not load the speech synthesizer", t)
                runCatching { tts?.release() }
                tts = null
                _ttsState.value = SpeechEngineState.ERROR
                _ttsError.value = t.message ?: "Could not load ${ttsModel.name}"
            }
        }
    }

    private suspend fun transcribeInternal(samples: FloatArray, sampleRate: Int): String =
        withContext(Dispatchers.IO) {
            recognizerMutex.withLock {
                val engine = recognizer ?: return@withLock ""
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    engine.decode(stream)
                    engine.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }
        }

    /**
     * Transcribes [samples] (mono, normalized to [-1, 1]). Returns an empty
     * string when STT is off or the clip is empty.
     */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): String {
        if (!_sttEnabled.value || samples.isEmpty()) return ""
        ensureSttReady()

        _isTranscribing.value = true
        return try {
            transcribeInternal(samples, sampleRate)
        } catch (t: Throwable) {
            Log.e(TAG, "Transcription failed", t)
            _sttError.value = t.message ?: "Transcription failed"
            ""
        } finally {
            _isTranscribing.value = false
        }
    }

    /**
     * Synthesizes [text]. Markdown decoration and code blocks are stripped
     * first: reading punctuation out loud is worse than skipping it.
     */
    suspend fun synthesize(text: String): SpeechAudio? {
        if (!_ttsEnabled.value) return null
        val spoken = SpeechTextNormalizer.clean(text)
        if (spoken.isBlank()) return null

        ensureTtsReady()

        _isSynthesizing.value = true
        return try {
            withContext(Dispatchers.IO) {
                ttsMutex.withLock {
                    val engine = tts ?: return@withLock null
                    val audio = engine.generate(spoken, sid = 0, speed = 1.0f)
                    if (audio.samples.isEmpty()) {
                        null
                    } else {
                        SpeechAudio(samples = audio.samples, sampleRate = audio.sampleRate)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Speech synthesis failed", t)
            _ttsError.value = t.message ?: "Speech synthesis failed"
            null
        } finally {
            _isSynthesizing.value = false
        }
    }

    private suspend fun unloadStt() {
        recognizerMutex.withLock {
            runCatching { recognizer?.release() }
            recognizer = null
        }
        _sttState.value = if (_sttEnabled.value) SpeechEngineState.UNLOADED
        else SpeechEngineState.DISABLED
    }

    private suspend fun unloadTts() {
        ttsMutex.withLock {
            runCatching { tts?.release() }
            tts = null
        }
        _ttsState.value = if (_ttsEnabled.value) SpeechEngineState.UNLOADED
        else SpeechEngineState.DISABLED
    }

    private fun stateFromDisk(enabled: Boolean, model: SpeechModelProfile): SpeechEngineState =
        when {
            !enabled -> SpeechEngineState.DISABLED
            model.isReady(downloads.speechDir) -> SpeechEngineState.UNLOADED
            else -> SpeechEngineState.NOT_DOWNLOADED
        }

    private companion object {
        const val TAG = "SpeechEngineManager"
        const val SAMPLE_RATE = 16_000
    }
}