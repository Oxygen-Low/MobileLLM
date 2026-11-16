package com.example.model

import android.content.Context
import android.util.Log
import com.example.data.ImagePreferences
import com.example.image.GeneratedImage
import com.example.image.GeneratedImageStore
import com.example.image.ImageGenerationRequest
import com.example.image.PngWriter
import com.example.model.internal.ImageBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Lifecycle of the image engine. */
enum class ImageEngineState {
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
 * Owns the on-device image model.
 *
 * The engine is deliberately short lived: the chat model and a diffusion
 * checkpoint do not fit in memory at the same time, so the orchestrator in the
 * view model releases the chat weights before [generate] is called and brings
 * them back afterwards. Nothing is allocated until a picture is actually asked
 * for, so an image model that is only downloaded costs storage, not RAM.
 *
 * The same [ModelDownloadManager] that fetches the GGUF chat models stores the
 * image weights: an image model is a single GGUF file, exactly like a chat one.
 */
class ImageEngineManager(
    context: Context,
    private val downloads: ModelDownloadManager,
    private val store: GeneratedImageStore
) {
    private val preferences = ImagePreferences(context.applicationContext)

    private val _selected = MutableStateFlow(modelFromId(preferences.imageModelId))
    val selected: StateFlow<ImageModelProfile> = _selected.asStateFlow()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _state = MutableStateFlow(stateFromDisk(modelFromId(preferences.imageModelId)))
    val state: StateFlow<ImageEngineState> = _state.asStateFlow()

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
     * Makes [model] the model pictures are generated with.
     *
     * The resident engine holds the weights of the previous model, so they are
     * released before the selection changes.
     */
    suspend fun selectModel(model: ImageModelProfile) {
        if (model.id == _selected.value.id) return

        preferences.imageModelId = model.id
        _selected.value = model
        _error.value = null
        unloadInternal()
        _state.value = stateFromDisk(model)
    }

    /** Downloads the weights of [model] without generating anything yet. */
    suspend fun downloadModel(model: ImageModelProfile) {
        downloadWeights(model)
    }

    /**
     * Removes the weights of [model].
     *
     * When the model the app would generate with is deleted, another model that
     * is still on the device takes over, so a later request does not fail on a
     * missing file.
     */
    suspend fun deleteModel(model: ImageModelProfile) {
        if (_selected.value.id == model.id) {
            unloadInternal()
        }
        downloads.deleteModel(model.id)
        refreshDownloaded()

        if (_selected.value.id != model.id) {
            _state.value = stateFromDisk(_selected.value)
            return
        }

        val replacement = ImageModelProfile.MODELS.firstOrNull {
            it.id != model.id && it.isReady(downloads.modelsDir)
        } ?: ImageModelProfile.MODELS.firstOrNull { it.id != model.id }
            ?: ImageModelProfile.DEFAULT_IMAGE_MODEL

        preferences.imageModelId = replacement.id
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
        if (modelId == _selected.value.id && _state.value == ImageEngineState.DOWNLOADING) {
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
            _state.value = ImageEngineState.ERROR
            _error.value = downloads.downloadStates.value[model.id]?.error
                ?: "Could not prepare ${model.name}"
            return false
        }
        return load()
    }

    /**
     * Samples one picture and stores it with [sessionId].
     *
     * Returns null (and sets [error]) when no model is ready or the engine
     * fails. The caller is responsible for releasing the chat weights first:
     * the two engines do not fit in memory together.
     */
    suspend fun generate(request: ImageGenerationRequest, sessionId: String): GeneratedImage? {
        _error.value = null
        if (request.prompt.isBlank()) {
            _error.value = "The image description was empty"
            return null
        }
        if (!ensureReady()) return null

        val active = handle
        if (active == 0L) {
            _error.value = "No image model is loaded"
            return null
        }

        _isGenerating.value = true
        return try {
            val seed = request.seed ?: Random.nextInt()
            val pixels = withContext(Dispatchers.IO) {
                ImageBridge.nativeGenerateImage(
                    active,
                    request.prompt,
                    request.width,
                    request.height,
                    request.steps,
                    seed
                )
            }
            if (pixels == null) {
                _error.value = "The image engine could not draw the picture"
                null
            } else {
                val png = PngWriter.encodeRgba(request.width, request.height, pixels)
                store.save(sessionId, png, request.prompt, request.width, request.height)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Image generation failed", t)
            _error.value = t.message ?: "Image generation failed"
            null
        } finally {
            _isGenerating.value = false
        }
    }

    /** Downloads the weights of [model] when they are missing. */
    private suspend fun downloadWeights(model: ImageModelProfile) {
        if (model.isReady(downloads.modelsDir)) {
            refreshDownloaded()
            return
        }
        downloadMutex.withLock {
            if (model.isReady(downloads.modelsDir)) return

            if (model.id == _selected.value.id) {
                _state.value = ImageEngineState.DOWNLOADING
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
                    _state.value = ImageEngineState.ERROR
                    _error.value = downloads.downloadStates.value[model.id]?.error
                        ?: "Could not download ${model.name}"
                } else if (_state.value == ImageEngineState.DOWNLOADING) {
                    _state.value = ImageEngineState.UNLOADED
                }
            }
        }
    }

    /** Maps the selected weights into memory. The caller holds no lock. */
    private suspend fun load(): Boolean = loadMutex.withLock {
        if (handle != 0L) {
            _state.value = ImageEngineState.READY
            return@withLock true
        }

        val model = _selected.value
        if (!model.isReady(downloads.modelsDir)) {
            _state.value = ImageEngineState.NOT_DOWNLOADED
            return@withLock false
        }

        _state.value = ImageEngineState.LOADING
        val file = downloads.getModelFile(model.id)
        try {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val newHandle = withContext(Dispatchers.IO) {
                ImageBridge.nativeLoadImageModel(file.absolutePath, threads)
            }
            if (newHandle == 0L) {
                _state.value = ImageEngineState.ERROR
                _error.value = "The image engine could not load ${model.name}"
                false
            } else {
                handle = newHandle
                _state.value = ImageEngineState.READY
                Log.i(TAG, "${model.name} loaded")
                true
            }
        } catch (c: CancellationException) {
            _state.value = ImageEngineState.UNLOADED
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load the image model", t)
            releaseHandle()
            _state.value = ImageEngineState.ERROR
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
            runCatching { ImageBridge.nativeFreeImageModel(current) }
                .onFailure { Log.e(TAG, "nativeFreeImageModel failed", it) }
        }
    }

    private fun refreshDownloaded() {
        _downloaded.value = ImageModelProfile.MODELS
            .filter { it.isReady(downloads.modelsDir) }
            .map { it.id }
            .toSet()
    }

    private fun stateFromDisk(model: ImageModelProfile): ImageEngineState =
        if (model.isReady(downloads.modelsDir)) {
            ImageEngineState.UNLOADED
        } else {
            ImageEngineState.NOT_DOWNLOADED
        }

    /** The model with the given id, or the default when the id is unknown. */
    private fun modelFromId(id: String): ImageModelProfile =
        ImageModelProfile.MODELS.firstOrNull { it.id == id }
            ?: ImageModelProfile.DEFAULT_IMAGE_MODEL

    private companion object {
        const val TAG = "ImageEngineManager"
    }
}
