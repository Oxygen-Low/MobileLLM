package com.example.model

import android.content.Context
import android.util.Log
import com.example.data.VideoPreferences
import com.example.video.GeneratedVideo
import com.example.video.GeneratedVideoStore
import com.example.video.Mp4Writer
import com.example.video.VideoGenerationRequest
import com.example.model.internal.VideoBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Lifecycle of the video engine. */
enum class VideoEngineState {
    /** The selected model is not on the device yet. */
    NOT_DOWNLOADED,

    /** Weights are being transferred. */
    DOWNLOADING,

    /** Weights are being mapped into memory. */
    LOADING,

    /** Resident and ready to sample. */
    READY,

    /** On the device but not resident: the next request loads it. */
    UNLOADED,

    /** Something went wrong while downloading, loading or sampling. */
    ERROR
}

/**
 * Owns the on-device video model.
 *
 * The engine is deliberately short lived: the chat model and a video
 * checkpoint do not fit in memory at the same time, so the orchestrator in the
 * view model releases the chat weights before [generate] is called and brings
 * them back afterwards. The video weights are released again before the clip
 * is muxed, so the encoder runs with the model out of memory.
 *
 * The same [ModelDownloadManager] that fetches the GGUF chat models stores the
 * video weights: a video model is a single GGUF file, exactly like a chat one.
 */
class VideoEngineManager(
    context: Context,
    private val downloads: ModelDownloadManager,
    private val store: GeneratedVideoStore
) {
    private val preferences = VideoPreferences(context.applicationContext)

    private val _selected = MutableStateFlow(modelFromId(preferences.videoModelId))
    val selected: StateFlow<VideoModelProfile> = _selected.asStateFlow()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _state = MutableStateFlow(stateFromDisk(modelFromId(preferences.videoModelId)))
    val state: StateFlow<VideoEngineState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var handle = 0L
    private val loadMutex = Mutex()
    private val downloadMutex = Mutex()

    /** Reconciles the state with what is on disk. Called once at app start. */
    fun refresh() {
        refreshDownloaded()
        if (handle == 0L) {
            _state.value = stateFromDisk(_selected.value)
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Makes [model] the model clips are generated with.
     *
     * The resident engine holds the weights of the previous model, so they are
     * released before the selection changes.
     */
    suspend fun selectModel(model: VideoModelProfile) {
        if (model.id == _selected.value.id) return

        preferences.videoModelId = model.id
        _selected.value = model
        _error.value = null
        unloadInternal()
        _state.value = stateFromDisk(model)
    }

    /** Downloads the weights of [model] without generating anything yet. */
    suspend fun downloadModel(model: VideoModelProfile) {
        downloadWeights(model)
    }

    /**
     * Removes the weights of [model].
     *
     * When the model the app would generate with is deleted, another model that
     * is still on the device takes over, so a later request does not fail on a
     * missing file.
     */
    suspend fun deleteModel(model: VideoModelProfile) {
        if (_selected.value.id == model.id) {
            unloadInternal()
        }
        downloads.deleteModel(model.id)
        refreshDownloaded()

        if (_selected.value.id != model.id) {
            _state.value = stateFromDisk(_selected.value)
            return
        }

        val replacement = VideoModelProfile.MODELS.firstOrNull {
            it.id != model.id && it.isReady(downloads.modelsDir)
        } ?: VideoModelProfile.MODELS.firstOrNull { it.id != model.id }
            ?: VideoModelProfile.DEFAULT_VIDEO_MODEL

        preferences.videoModelId = replacement.id
        _selected.value = replacement
        _state.value = stateFromDisk(replacement)
    }

    /** Releases the weights and leaves the state as it is found on disk. */
    suspend fun unload() {
        unloadInternal()
        _state.value = stateFromDisk(_selected.value)
    }

    /**
     * Brings the selected model back from "downloading" after the user stopped
     * the transfer.
     */
    fun onDownloadCancelled(modelId: String) {
        if (modelId == _selected.value.id && _state.value == VideoEngineState.DOWNLOADING) {
            _state.value = stateFromDisk(_selected.value)
        }
        refreshDownloaded()
    }

    /**
     * Loads the selected model if it is not resident yet, downloading it first
     * when necessary. Safe to call before every request.
     */
    suspend fun ensureReady(): Boolean {
        val model = _selected.value
        if (!model.isReady(downloads.modelsDir)) {
            downloadWeights(model)
        }
        if (!model.isReady(downloads.modelsDir)) {
            _state.value = VideoEngineState.ERROR
            _error.value = downloads.downloadStates.value[model.id]?.error
                ?: "Could not prepare ${model.name}"
            return false
        }
        return load()
    }

    /**
     * Samples one clip and stores it with [sessionId].
     *
     * The frames and the soundtrack are sampled by the same engine, muxed into
     * one MP4 and written next to the pictures of the chat. The weights are
     * released before the encoder starts so the clip does not compete with the
     * model for memory.
     *
     * Returns null (and sets [error]) when no model is ready or the engine
     * fails. The caller is responsible for releasing the chat weights first.
     */
    suspend fun generate(request: VideoGenerationRequest, sessionId: String): GeneratedVideo? {
        _error.value = null
        if (request.prompt.isBlank()) {
            _error.value = "The video description was empty"
            return null
        }
        if (request.frameCount <= 0 || request.fps <= 0) {
            _error.value = "The video request had no frames"
            return null
        }
        if (!ensureReady()) return null

        val active = handle
        if (active == 0L) {
            _error.value = "No video model is loaded"
            return null
        }

        _isGenerating.value = true
        return try {
            val seed = request.seed ?: Random.nextInt()
            val model = _selected.value
            val frames = withContext(Dispatchers.IO) {
                VideoBridge.nativeGenerateVideo(
                    active,
                    request.prompt,
                    request.width,
                    request.height,
                    request.frameCount,
                    request.steps,
                    seed
                )
            }
            val expectedFrames = request.frameCount.toLong() * request.width * request.height * 4
            if (frames == null || frames.size < expectedFrames) {
                _error.value = "The video engine could not draw the clip"
                return null
            }

            val pcm = if (model.hasAudio) {
                withContext(Dispatchers.IO) {
                    VideoBridge.nativeGenerateAudio(
                        active,
                        request.prompt,
                        request.frameCount,
                        request.fps,
                        Mp4Writer.SAMPLE_RATE,
                        seed
                    )
                }
            } else {
                null
            }
            if (model.hasAudio && (pcm == null || pcm.size < 2)) {
                _error.value = "The video engine could not synthesize the soundtrack"
                return null
            }

            val pending = store.newFile(sessionId)
            val encoded = withContext(Dispatchers.IO) {
                Mp4Writer.write(
                    file = pending.file,
                    width = request.width,
                    height = request.height,
                    fps = request.fps,
                    frames = frames,
                    frameCount = request.frameCount,
                    pcm = pcm ?: ByteArray(0)
                )
            }
            if (!encoded) {
                pending.file.delete()
                _error.value = "The clip could not be encoded"
                null
            } else {
                store.complete(
                    sessionId = sessionId,
                    pending = pending,
                    prompt = request.prompt,
                    width = request.width,
                    height = request.height,
                    durationMs = request.frameCount * 1000L / request.fps,
                    hasAudio = model.hasAudio
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Video generation failed", t)
            _error.value = t.message ?: "Video generation failed"
            null
        } finally {
            // The weights are not needed by the encoder, and on every failed
            // path they are not needed either. Giving the memory back here is
            // what lets a big checkpoint run on a phone next to the chat model.
            unloadInternal()
            _state.value = stateFromDisk(_selected.value)
            _isGenerating.value = false
        }
    }

    /** Downloads the weights of [model] when they are missing. */
    private suspend fun downloadWeights(model: VideoModelProfile) {
        if (model.isReady(downloads.modelsDir)) {
            refreshDownloaded()
            return
        }
        downloadMutex.withLock {
            if (model.isReady(downloads.modelsDir)) return

            if (model.id == _selected.value.id) {
                _state.value = VideoEngineState.DOWNLOADING
            }
            downloads.startDownload(
                modelId = model.id,
                repo = model.modelScopeRepo,
                filename = model.modelScopeFile,
                totalMb = model.downloadSizeMb,
                hfRepo = model.hfRepo,
                hfFilename = model.hfFile
            ) { }
            refreshDownloaded()

            // startDownload reports a failed transfer through the shared
            // download states instead of throwing, so the engine has to read
            // that report to leave "downloading" behind.
            if (model.id == _selected.value.id) {
                if (!model.isReady(downloads.modelsDir)) {
                    _state.value = VideoEngineState.ERROR
                    _error.value = downloads.downloadStates.value[model.id]?.error
                        ?: "Could not download ${model.name}"
                } else if (_state.value == VideoEngineState.DOWNLOADING) {
                    _state.value = VideoEngineState.UNLOADED
                }
            }
        }
    }

    /** Maps the selected weights into memory. The caller holds no lock. */
    private suspend fun load(): Boolean = loadMutex.withLock {
        if (handle != 0L) {
            _state.value = VideoEngineState.READY
            return@withLock true
        }

        val model = _selected.value
        if (!model.isReady(downloads.modelsDir)) {
            _state.value = VideoEngineState.NOT_DOWNLOADED
            return@withLock false
        }

        _state.value = VideoEngineState.LOADING
        val file = downloads.getModelFile(model.id)
        try {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val newHandle = withContext(Dispatchers.IO) {
                VideoBridge.nativeLoadVideoModel(file.absolutePath, threads)
            }
            if (newHandle == 0L) {
                _state.value = VideoEngineState.ERROR
                _error.value = "The video engine could not load ${model.name}"
                false
            } else {
                handle = newHandle
                _state.value = VideoEngineState.READY
                Log.i(TAG, "${model.name} loaded")
                true
            }
        } catch (c: CancellationException) {
            _state.value = VideoEngineState.UNLOADED
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load the video model", t)
            releaseHandle()
            _state.value = VideoEngineState.ERROR
            _error.value = t.message ?: "Could not load ${model.name}"
            false
        }
    }

    private suspend fun unloadInternal() {
        loadMutex.withLock {
            releaseHandle()
        }
    }

    /** Frees the native engine. The caller holds the load lock. */
    private fun releaseHandle() {
        val current = handle
        handle = 0L
        if (current != 0L) {
            runCatching { VideoBridge.nativeFreeVideoModel(current) }
                .onFailure { Log.e(TAG, "nativeFreeVideoModel failed", it) }
        }
    }

    private fun refreshDownloaded() {
        _downloaded.value = VideoModelProfile.MODELS
            .filter { it.isReady(downloads.modelsDir) }
            .map { it.id }
            .toSet()
    }

    private fun stateFromDisk(model: VideoModelProfile): VideoEngineState =
        if (model.isReady(downloads.modelsDir)) {
            VideoEngineState.UNLOADED
        } else {
            VideoEngineState.NOT_DOWNLOADED
        }

    /** The model with the given id, or the default when the id is unknown. */
    private fun modelFromId(id: String): VideoModelProfile =
        VideoModelProfile.MODELS.firstOrNull { it.id == id }
            ?: VideoModelProfile.DEFAULT_VIDEO_MODEL

    private companion object {
        const val TAG = "VideoEngineManager"
    }
}