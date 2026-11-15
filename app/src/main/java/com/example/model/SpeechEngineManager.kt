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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    /** Something went wrong while downloading or loading. */
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
 *
 * Text to Speech speaks with one of the Piper voices in
 * [SpeechModelProfile.TEXT_TO_SPEECH_MODELS]. The user picks one of them and
 * the choice is remembered across launches.
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

    private val _sttState = MutableStateFlow(
        stateFromDisk(preferences.speechToTextEnabled, SpeechModelProfile.WHISPER_TINY)
    )
    val sttState: StateFlow<SpeechEngineState> = _sttState.asStateFlow()

    private val _ttsState = MutableStateFlow(
        stateFromDisk(
            preferences.textToSpeechEnabled,
            voiceFromId(preferences.textToSpeechVoiceId)
        )
    )
    val ttsState: StateFlow<SpeechEngineState> = _ttsState.asStateFlow()

    private val _sttError = MutableStateFlow<String?>(null)
    val sttError: StateFlow<String?> = _sttError.asStateFlow()

    private val _ttsError = MutableStateFlow<String?>(null)
    val ttsError: StateFlow<String?> = _ttsError.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing: StateFlow<Boolean> = _isSynthesizing.asStateFlow()

    /** The Piper voice that is spoken with. */
    private val _ttsVoice = MutableStateFlow(voiceFromId(preferences.textToSpeechVoiceId))
    val ttsVoice: StateFlow<SpeechModelProfile> = _ttsVoice.asStateFlow()

    /** The ids of the voices that are already on the device. */
    private val _downloadedVoices = MutableStateFlow<Set<String>>(emptySet())
    val downloadedVoices: StateFlow<Set<String>> = _downloadedVoices.asStateFlow()

    private var recognizer: OfflineRecognizer? = null
    private var tts: OfflineTts? = null

    /** The voice the resident synthesizer speaks with. */
    private var residentVoiceId: String? = null

    private val recognizerMutex = Mutex()
    private val ttsMutex = Mutex()
    private val sttDownloadMutex = Mutex()
    private val ttsDownloadMutex = Mutex()

    val sttModel: SpeechModelProfile = SpeechModelProfile.WHISPER_TINY

    /**
     * Reconciles the state with what is actually on disk. Called once when the
     * view model is created, after the download folder has been scanned.
     */
    fun refresh() {
        // Older installs kept the espeak-ng data inside the voice directory;
        // it is shared by all of the voices now.
        downloads.migrateSharedEspeakData()
        refreshDownloadedVoices()
        if (recognizer == null) {
            _sttState.value = stateFromDisk(sttEnabled.value, sttModel)
        }
        if (tts == null) {
            _ttsState.value = stateFromDisk(ttsEnabled.value, _ttsVoice.value)
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

    /**
     * Picks the voice that is spoken from now on.
     *
     * The resident synthesizer speaks with the old voice, so it is released
     * and the new one is loaded. A voice that is not on the device yet is
     * downloaded first.
     */
    suspend fun selectTtsVoice(voice: SpeechModelProfile) {
        if (voice.id == _ttsVoice.value.id) return

        preferences.textToSpeechVoiceId = voice.id
        _ttsVoice.value = voice
        _ttsError.value = null

        // The engine speaks with the previous voice; drop it before the new
        // weights are mapped in.
        unloadTts()

        if (_ttsEnabled.value) {
            ensureTtsReady()
        } else {
            _ttsState.value = SpeechEngineState.DISABLED
        }
    }

    /**
     * Downloads a voice without speaking with it yet.
     *
     * Picking a voice in the model hub always switches to it, but a user who
     * only wants to hear a voice first has to fetch it somehow.
     */
    suspend fun downloadVoice(voice: SpeechModelProfile) {
        if (voice.isReady(downloads.speechDir)) {
            refreshDownloadedVoices()
            return
        }
        downloadVoiceWeights(voice)
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
        ensureVoiceResident(_ttsVoice.value)
    }

    /**
     * Synthesizes [text]. Markdown decoration and code blocks are stripped
     * first: reading punctuation out loud is worse than skipping it.
     */
    suspend fun synthesize(text: String): SpeechAudio? {
        if (!_ttsEnabled.value) return null
        val spoken = SpeechTextNormalizer.clean(text)
        if (spoken.isBlank()) return null

        if (!ensureVoiceResident(_ttsVoice.value)) return null

        _isSynthesizing.value = true
        return try {
            generate(spoken)
        } catch (t: Throwable) {
            Log.e(TAG, "Speech synthesis failed", t)
            _ttsError.value = t.message ?: "Speech synthesis failed"
            null
        } finally {
            _isSynthesizing.value = false
        }
    }

    /**
     * Speaks a short sample with [voice] so the user can hear it.
     *
     * The voice has to be on the device already; downloading it is a separate
     * step so that a stray tap does not start a transfer. The weights of the
     * voice the app speaks with are restored afterwards.
     */
    suspend fun previewVoice(voice: SpeechModelProfile, text: String): SpeechAudio? {
        if (!voice.isReady(downloads.speechDir)) {
            _ttsError.value = "Download ${voice.name} before previewing it"
            return null
        }

        val spoken = SpeechTextNormalizer.clean(text)
        if (spoken.isBlank()) return null

        val selected = _ttsVoice.value
        _isSynthesizing.value = true
        try {
            return speakWith(voice, spoken)
        } catch (c: CancellationException) {
            // The user stopped the preview; the finally block still hands the
            // synthesizer back.
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "The voice preview failed", t)
            _ttsError.value = t.message ?: "Could not preview ${voice.name}"
            return null
        } finally {
            _isSynthesizing.value = false
            // The preview borrowed the synthesizer, so it has to be given
            // back - also when the preview failed halfway through, and also
            // when the coroutine was cancelled while it spoke.
            withContext(NonCancellable) {
                restoreAfterPreview(selected, voice)
            }
        }
    }

    /**
     * Gives the synthesizer back to the voice the app reads replies with.
     *
     * While Text to Speech is off the weights are released entirely, so that
     * a preview does not keep them in memory.
     */
    private suspend fun restoreAfterPreview(
        selected: SpeechModelProfile,
        previewed: SpeechModelProfile
    ) {
        if (!_ttsEnabled.value) {
            unloadTts()
            return
        }
        if (previewed.id == selected.id) return
        restoreResidentVoice(selected)
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
     * Makes sure the synthesizer speaks with [voice]. Downloads the weights if
     * they are missing and loads them into memory.
     */
    private suspend fun ensureVoiceResident(voice: SpeechModelProfile): Boolean {
        if (tts != null && residentVoiceId == voice.id) {
            _ttsState.value = SpeechEngineState.READY
            return true
        }

        downloadVoiceWeights(voice)

        if (!voice.isReady(downloads.speechDir)) {
            _ttsState.value = SpeechEngineState.ERROR
            _ttsError.value = downloads.downloadStates.value[voice.id]?.error
                ?: "Could not prepare ${voice.name}"
            return false
        }

        return ttsMutex.withLock {
            if (tts != null && residentVoiceId == voice.id) return@withLock true

            // Another voice is still resident; it has to go before the new
            // weights can be mapped in.
            releaseTtsEngine()
            _ttsState.value = SpeechEngineState.LOADING
            try {
                loadVoiceWeights(voice)
                _ttsState.value = SpeechEngineState.READY
                Log.i(TAG, "${voice.name} loaded")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Could not load the speech synthesizer", t)
                releaseTtsEngine()
                _ttsState.value = SpeechEngineState.ERROR
                _ttsError.value = t.message ?: "Could not load ${voice.name}"
                false
            }
        }
    }

    /**
     * Downloads the weights of a voice if they are not on the device yet.
     *
     * Only the voice the app speaks with drives the card state; a voice that
     * is fetched just to be previewed reports its progress in the download
     * list instead.
     */
    private suspend fun downloadVoiceWeights(voice: SpeechModelProfile) {
        val isSelected = voice.id == _ttsVoice.value.id
        ttsDownloadMutex.withLock {
            if (voice.isReady(downloads.speechDir)) return
            if (isSelected) {
                _ttsState.value = SpeechEngineState.DOWNLOADING
            }
            downloads.startSpeechDownload(voice)
            refreshDownloadedVoices()
        }
    }

    /**
     * Speaks [spoken] with [voice], loading it into memory first when needed.
     */
    private suspend fun speakWith(voice: SpeechModelProfile, spoken: String): SpeechAudio? =
        withContext(Dispatchers.IO) {
            ttsMutex.withLock {
                if (tts == null || residentVoiceId != voice.id) {
                    releaseTtsEngine()
                    loadVoiceWeights(voice)
                }
                val engine = tts ?: return@withLock null
                val audio = engine.generate(spoken, sid = 0, speed = 1.0f)
                if (audio.samples.isEmpty()) {
                    null
                } else {
                    SpeechAudio(samples = audio.samples, sampleRate = audio.sampleRate)
                }
            }
        }

    /**
     * Loads the weights of [voice]. The caller holds the synthesizer lock.
     */
    private suspend fun loadVoiceWeights(voice: SpeechModelProfile) {
        withContext(Dispatchers.IO) {
            val dir = downloads.getSpeechModelDir(voice.id)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(dir, voice.voiceFile ?: "").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        // The espeak-ng data is shared by all of the voices.
                        dataDir = downloads.espeakDir.absolutePath
                    ),
                    numThreads = threads
                )
            )
            tts = OfflineTts(config = config)
            residentVoiceId = voice.id
        }
    }

    /**
     * Gives the synthesizer back to the voice the app reads replies with. When
     * the feature is off the weights are released entirely.
     */
    private suspend fun restoreResidentVoice(voice: SpeechModelProfile) {
        if (!_ttsEnabled.value) {
            unloadTts()
            return
        }
        if (tts != null && residentVoiceId == voice.id) return
        if (!voice.isReady(downloads.speechDir)) return
        ttsMutex.withLock {
            if (tts != null && residentVoiceId == voice.id) return@withLock
            releaseTtsEngine()
            try {
                loadVoiceWeights(voice)
                _ttsState.value = SpeechEngineState.READY
            } catch (t: Throwable) {
                Log.e(TAG, "Could not restore the speech synthesizer", t)
                releaseTtsEngine()
                _ttsState.value = SpeechEngineState.ERROR
                _ttsError.value = t.message ?: "Could not load ${voice.name}"
            }
        }
    }

    private suspend fun generate(spoken: String): SpeechAudio? =
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
            releaseTtsEngine()
        }
        _ttsState.value = if (_ttsEnabled.value) SpeechEngineState.UNLOADED
        else SpeechEngineState.DISABLED
    }

    /** Releases the native synthesizer. The caller holds the lock. */
    private fun releaseTtsEngine() {
        runCatching { tts?.release() }
        tts = null
        residentVoiceId = null
    }

    /**
     * Scans the speech folder and remembers which voices are on the device.
     */
    private fun refreshDownloadedVoices() {
        _downloadedVoices.value = SpeechModelProfile.TEXT_TO_SPEECH_MODELS
            .filter { it.isReady(downloads.speechDir) }
            .map { it.id }
            .toSet()
    }

    private fun stateFromDisk(enabled: Boolean, model: SpeechModelProfile): SpeechEngineState =
        when {
            !enabled -> SpeechEngineState.DISABLED
            model.isReady(downloads.speechDir) -> SpeechEngineState.UNLOADED
            else -> SpeechEngineState.NOT_DOWNLOADED
        }

    /**
     * The voice with the given id, or the default one when the id is unknown
     * or missing. A voice that was removed from the catalogue falls back to
     * the default instead of leaving the synthesizer without weights.
     */
    private fun voiceFromId(id: String): SpeechModelProfile =
        SpeechModelProfile.TEXT_TO_SPEECH_MODELS.firstOrNull { it.id == id }
            ?: SpeechModelProfile.DEFAULT_TTS_VOICE

    private companion object {
        const val TAG = "SpeechEngineManager"
        const val SAMPLE_RATE = 16_000
    }
}
