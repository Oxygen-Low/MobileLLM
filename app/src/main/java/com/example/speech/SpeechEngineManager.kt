package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.model.AudioModelProfile
import com.example.model.ModelDownloadManager
import com.example.model.ModelDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class SpeechEngineManager(
    private val context: Context,
    private val downloadManager: ModelDownloadManager,
    private val scope: CoroutineScope
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    private var speechRecognizer: SpeechRecognizer? = null

    val ttsModel = MutableStateFlow(AudioModelProfile.PIPER_TTS)
    val sttModel = MutableStateFlow(AudioModelProfile.WHISPER_STT)

    private val _isTtsEnabled = MutableStateFlow(false)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private val _isSttEnabled = MutableStateFlow(false)
    val isSttEnabled: StateFlow<Boolean> = _isSttEnabled.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    init {
        // Initialize TTS
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }
                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                }
            })
            isTtsInitialized = true
        }
    }

    fun toggleTts(enabled: Boolean, onNeedDownload: (AudioModelProfile) -> Unit) {
        if (enabled) {
            val model = ttsModel.value
            if (model.downloadState != ModelDownloadState.DOWNLOADED && !downloadManager.isModelDownloaded(model.id)) {
                // Trigger download
                onNeedDownload(model)
                downloadTtsModel {
                    _isTtsEnabled.value = true
                }
            } else {
                _isTtsEnabled.value = true
            }
        } else {
            _isTtsEnabled.value = false
            stopSpeaking()
        }
    }

    fun toggleStt(enabled: Boolean, onNeedDownload: (AudioModelProfile) -> Unit) {
        if (enabled) {
            val model = sttModel.value
            if (model.downloadState != ModelDownloadState.DOWNLOADED && !downloadManager.isModelDownloaded(model.id)) {
                // Trigger download
                onNeedDownload(model)
                downloadSttModel {
                    _isSttEnabled.value = true
                }
            } else {
                _isSttEnabled.value = true
            }
        } else {
            _isSttEnabled.value = false
            stopListening()
        }
    }

    fun downloadTtsModel(onComplete: () -> Unit) {
        scope.launch {
            ttsModel.value = ttsModel.value.copy(downloadState = ModelDownloadState.DOWNLOADING)
            downloadManager.startDownload(
                modelId = ttsModel.value.id,
                repo = ttsModel.value.huggingFaceRepo,
                filename = ttsModel.value.huggingFaceFile,
                totalMb = ttsModel.value.sizeMb
            ) {
                ttsModel.value = ttsModel.value.copy(
                    downloadState = ModelDownloadState.DOWNLOADED,
                    downloadProgress = 1.0f
                )
                onComplete()
            }
        }
    }

    fun downloadSttModel(onComplete: () -> Unit) {
        scope.launch {
            sttModel.value = sttModel.value.copy(downloadState = ModelDownloadState.DOWNLOADING)
            downloadManager.startDownload(
                modelId = sttModel.value.id,
                repo = sttModel.value.huggingFaceRepo,
                filename = sttModel.value.huggingFaceFile,
                totalMb = sttModel.value.sizeMb
            ) {
                sttModel.value = sttModel.value.copy(
                    downloadState = ModelDownloadState.DOWNLOADED,
                    downloadProgress = 1.0f
                )
                onComplete()
            }
        }
    }

    fun speak(text: String) {
        if (!isTtsInitialized || text.isBlank()) return
        val cleanText = text.replace(Regex("```[\\s\\S]*?```"), "") // skip raw code blocks when speaking
            .replace(Regex("<[^>]*>"), "")
            .take(600)

        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "POCKETLM_UTTERANCE")
        _isSpeaking.value = true
    }

    fun stopSpeaking() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun startListening(onTextRecognized: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return
        }

        stopListening()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _isListening.value = false
                }
                override fun onError(error: Int) {
                    _isListening.value = false
                }
                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        _recognizedText.value = text
                        onTextRecognized(text)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        _recognizedText.value = matches[0]
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.startListening(intent)
        _isListening.value = true
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        _isListening.value = false
    }

    fun shutdown() {
        stopSpeaking()
        stopListening()
        tts?.shutdown()
        tts = null
    }
}
