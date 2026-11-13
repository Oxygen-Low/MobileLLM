package com.example.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class DownloadProgressInfo(
    val modelId: String,
    val progress: Float, // 0.0 to 1.0
    val downloadedMb: Float,
    val totalMb: Float,
    val speedMbps: Float,
    val state: ModelDownloadState,
    val error: String? = null
)

/**
 * Downloads real GGUF weights over HTTP(S), resuming partial transfers and
 * validating the file before it is handed to the inference engine.
 *
 * Nothing is generated locally: if the network transfer fails, the download
 * fails and the UI is told why.
 */
class ModelDownloadManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    /** Speech models are directories, so they live next to the GGUF files. */
    val speechDir = File(context.filesDir, "speech").apply { mkdirs() }

    private val _downloadStates = MutableStateFlow<Map<String, DownloadProgressInfo>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadProgressInfo>> = _downloadStates.asStateFlow()

    fun getModelFile(modelId: String): File = File(modelsDir, "$modelId.gguf")

    fun getSpeechModelDir(modelId: String): File = File(speechDir, modelId)

    fun isSpeechModelDownloaded(model: SpeechModelProfile): Boolean =
        model.isReady(speechDir)

    fun isModelDownloaded(modelId: String): Boolean {
        val file = getModelFile(modelId)
        if (!file.isFile || file.length() < 1024L * 1024L) return false
        return try {
            file.inputStream().use { GgufParser.parse(it).isValid }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun startDownload(
        modelId: String,
        repo: String,
        filename: String,
        totalMb: Int,
        hfRepo: String? = null,
        hfFilename: String? = null,
        onComplete: (File) -> Unit
    ) = withContext(Dispatchers.IO) {
        val target = getModelFile(modelId)
        val expectedBytes = totalMb.toLong() * 1024L * 1024L

        if (isModelDownloaded(modelId)) {
            publish(modelId, 1f, expectedBytes.toFloat(), expectedBytes.toFloat(), 0f, ModelDownloadState.DOWNLOADED)
            onComplete(target)
            return@withContext
        }

        val part = File(modelsDir, "$modelId.gguf.part")
        val sources = buildList {
            add(buildString { append("https://modelscope.cn/models/"); append(repo); append("/resolve/master/"); append(filename) })
            val hf = hfRepo ?: repo
            val hfFile = hfFilename ?: filename
            add("https://huggingface.co/$hf/resolve/main/$hfFile")
            add("https://hf-mirror.com/$hf/resolve/main/$hfFile")
        }

        var lastError: String? = null

        for (url in sources) {
            try {
                Log.i(TAG, "Downloading $modelId from $url")
                if (downloadFrom(url, part, expectedBytes, modelId, totalMb)) {
                    if (!validateGguf(part)) {
                        lastError = "Downloaded file is not a valid GGUF model"
                        part.delete()
                        continue
                    }

                    if (target.exists() && !target.delete()) {
                        throw IllegalStateException("Could not replace ${target.name}")
                    }
                    if (!part.renameTo(target)) {
                        throw IllegalStateException("Could not move downloaded file into place")
                    }

                    publish(modelId, 1f, target.length().toFloat(), target.length().toFloat(), 0f,
                        ModelDownloadState.DOWNLOADED)
                    Log.i(TAG, "Model $modelId ready (${target.length() / (1024 * 1024)} MB)")
                    onComplete(target)
                    return@withContext
                }
                lastError = "Server returned an error for $url"
            } catch (e: CancellationException) {
                // Keep the .part file so the next attempt can resume.
                publish(modelId, progressOf(part, expectedBytes), part.length().toFloat(),
                    expectedBytes.toFloat(), 0f, ModelDownloadState.NOT_DOWNLOADED)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download attempt failed: $url", e)
                lastError = e.message ?: "Network error"
            }
        }

        publish(
            modelId = modelId,
            progress = 0f,
            downloadedBytes = 0f,
            totalBytes = expectedBytes.toFloat(),
            speedMbps = 0f,
            state = ModelDownloadState.ERROR,
            error = lastError ?: "Could not download the model from any mirror"
        )
    }

    /**
     * Downloads and unpacks a small speech model. The archive is kept around
     * until extraction succeeds, so a failed unpack can be retried without
     * transferring the weights again.
     */
    suspend fun startSpeechDownload(
        profile: SpeechModelProfile,
        onComplete: (File) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val target = getSpeechModelDir(profile.id)
        if (profile.isReady(speechDir)) {
            publish(
                modelId = profile.id,
                progress = 1f,
                downloadedBytes = 0f,
                totalBytes = 0f,
                speedMbps = 0f,
                state = ModelDownloadState.DOWNLOADED
            )
            onComplete(target)
            return@withContext
        }

        val archive = File(speechDir, "${profile.id}.tar.bz2")
        val part = File(speechDir, "${profile.id}.tar.bz2.part")
        val expectedBytes = profile.downloadSizeMb.toLong() * 1024L * 1024L

        try {
            if (!archive.isFile || archive.length() == 0L) {
                Log.i(TAG, "Downloading speech model ${profile.id} from ${profile.archiveUrl}")
                if (!downloadFrom(
                        url = profile.archiveUrl,
                        part = part,
                        expectedBytes = expectedBytes,
                        modelId = profile.id,
                        totalMb = profile.downloadSizeMb
                    )
                ) {
                    publish(
                        modelId = profile.id,
                        progress = 0f,
                        downloadedBytes = 0f,
                        totalBytes = expectedBytes.toFloat(),
                        speedMbps = 0f,
                        state = ModelDownloadState.ERROR,
                        error = "Could not download ${profile.name}"
                    )
                    return@withContext
                }

                if (archive.exists() && !archive.delete()) {
                    throw IOException("Could not replace ${archive.name}")
                }
                if (!part.renameTo(archive)) {
                    throw IOException("Could not move the downloaded speech archive into place")
                }
            }

            publish(
                modelId = profile.id,
                progress = 0.99f,
                downloadedBytes = archive.length().toFloat(),
                totalBytes = archive.length().toFloat(),
                speedMbps = 0f,
                state = ModelDownloadState.DOWNLOADING
            )

            extractSpeechArchive(archive, profile)
            archive.delete()

            if (profile.isReady(speechDir)) {
                publish(
                    modelId = profile.id,
                    progress = 1f,
                    downloadedBytes = 0f,
                    totalBytes = 0f,
                    speedMbps = 0f,
                    state = ModelDownloadState.DOWNLOADED
                )
                Log.i(TAG, "Speech model ${profile.id} ready")
                onComplete(target)
            } else {
                publish(
                    modelId = profile.id,
                    progress = 0f,
                    downloadedBytes = 0f,
                    totalBytes = 0f,
                    speedMbps = 0f,
                    state = ModelDownloadState.ERROR,
                    error = "The speech archive is missing ${profile.name} files"
                )
            }
        } catch (e: CancellationException) {
            // Keep the .part file so the next attempt can resume.
            publish(
                modelId = profile.id,
                progress = progressOf(part, expectedBytes),
                downloadedBytes = part.length().toFloat(),
                totalBytes = expectedBytes.toFloat(),
                speedMbps = 0f,
                state = ModelDownloadState.NOT_DOWNLOADED
            )
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Speech model download failed", e)
            publish(
                modelId = profile.id,
                progress = 0f,
                downloadedBytes = 0f,
                totalBytes = 0f,
                speedMbps = 0f,
                state = ModelDownloadState.ERROR,
                error = e.message ?: "Could not install ${profile.name}"
            )
        }
    }

    /**
     * Unpacks the tar.bz2 release into `speech/<id>`. Entries are checked
     * against the extraction directory so a malicious archive cannot write
     * outside of the app's private storage.
     */
    private fun extractSpeechArchive(archive: File, profile: SpeechModelProfile) {
        val temp = File(speechDir, "${profile.id}.extracting")
        temp.deleteRecursively()
        if (!temp.mkdirs()) throw IOException("Could not create ${temp.name}")

        val root = temp.canonicalFile
        try {
            TarArchiveInputStream(
                BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
            ).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val output = File(temp, entry.name).canonicalFile
                    if (!output.path.startsWith(root.path + File.separator) && output != root) {
                        throw IOException("Unsafe path in speech archive: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { stream -> tar.copyTo(stream) }
                    }
                    entry = tar.nextEntry
                }
            }

            val children = temp.listFiles().orEmpty()
            val source = children.singleOrNull()?.takeIf { it.isDirectory } ?: temp

            // Drop unquantised duplicates and sample audio that the runtime
            // never reads; speech storage is scarce next to the LLM weights.
            for (relative in profile.prunedFiles) {
                File(source, relative).deleteRecursively()
            }

            val destination = getSpeechModelDir(profile.id)
            destination.deleteRecursively()
            if (!source.renameTo(destination)) {
                source.copyRecursively(destination, overwrite = true)
                source.deleteRecursively()
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    fun deleteSpeechModel(modelId: String): Boolean {
        val deleted = getSpeechModelDir(modelId).deleteRecursively()
        File(speechDir, "$modelId.tar.bz2").delete()
        File(speechDir, "$modelId.tar.bz2.part").delete()
        File(speechDir, "$modelId.extracting").deleteRecursively()
        _downloadStates.value = _downloadStates.value.toMutableMap().apply { remove(modelId) }
        return deleted
    }

    /**
     * @return true when [part] holds the complete file.
     */
    private suspend fun downloadFrom(
        url: String,
        part: File,
        expectedBytes: Long,
        modelId: String,
        totalMb: Int
    ): Boolean {
        var alreadyHave = if (part.isFile) part.length() else 0L
        if (alreadyHave > 0 && expectedBytes > 0 && alreadyHave >= expectedBytes) {
            return true
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")

        if (alreadyHave > 0) {
            requestBuilder.header("Range", "bytes=$alreadyHave-")
        }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for $url")
                return false
            }

            val body = response.body ?: return false
            val serverSupportsResume = response.code == 206
            if (alreadyHave > 0 && !serverSupportsResume) {
                alreadyHave = 0L
                part.delete()
            }

            val contentLength = body.contentLength()
            val totalBytes = when {
                contentLength > 0 -> alreadyHave + contentLength
                expectedBytes > 0 -> expectedBytes
                else -> -1L
            }

            var downloaded = alreadyHave
            var windowStart = System.currentTimeMillis()
            var windowBytes = 0L
            var lastPublish = 0L

            FileOutputStream(part, alreadyHave > 0).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()

                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        windowBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastPublish >= 250) {
                            val seconds = ((now - windowStart).coerceAtLeast(1)) / 1000.0
                            val speed = (windowBytes / (1024.0 * 1024.0) / seconds).toFloat()
                            val progress = if (totalBytes > 0) {
                                (downloaded.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            publish(
                                modelId = modelId,
                                progress = progress,
                                downloadedBytes = downloaded.toFloat(),
                                totalBytes = if (totalBytes > 0) totalBytes.toFloat()
                                else totalMb.toFloat() * 1024f * 1024f,
                                speedMbps = speed,
                                state = ModelDownloadState.DOWNLOADING
                            )
                            lastPublish = now
                            windowStart = now
                            windowBytes = 0
                        }
                    }
                }
                output.fd.sync()
            }

            val complete = when {
                totalBytes > 0 -> part.length() >= totalBytes
                expectedBytes > 0 -> part.length() >= (expectedBytes * 98) / 100
                else -> part.length() > 0
            }

            if (!complete) {
                Log.w(TAG, "Incomplete transfer: ${part.length()} / $totalBytes bytes")
            }
            return complete
        }
    }

    private fun validateGguf(file: File): Boolean {
        if (!file.isFile || file.length() < 1024L * 1024L) return false
        return try {
            file.inputStream().use { stream ->
                val info = GgufParser.parse(stream)
                if (!info.isValid) {
                    Log.e(TAG, "GGUF validation failed: ${info.errorMessage}")
                } else {
                    Log.i(TAG, "GGUF ok: arch=${info.architecture} name=${info.modelName} quant=${info.quantizationType}")
                }
                info.isValid
            }
        } catch (e: Exception) {
            Log.e(TAG, "GGUF validation threw", e)
            false
        }
    }

    fun deleteModel(modelId: String): Boolean {
        val file = getModelFile(modelId)
        val deleted = file.delete()
        File(modelsDir, "$modelId.gguf.part").delete()
        _downloadStates.value = _downloadStates.value.toMutableMap().apply { remove(modelId) }
        return deleted
    }

    fun downloadedBytes(modelId: String): Long {
        val file = getModelFile(modelId)
        return if (file.isFile) file.length() else 0L
    }

    private fun progressOf(part: File, expectedBytes: Long): Float {
        if (expectedBytes <= 0) return 0f
        return (part.length().toDouble() / expectedBytes).toFloat().coerceIn(0f, 1f)
    }

    private fun publish(
        modelId: String,
        progress: Float,
        downloadedBytes: Float,
        totalBytes: Float,
        speedMbps: Float,
        state: ModelDownloadState,
        error: String? = null
    ) {
        val info = DownloadProgressInfo(
            modelId = modelId,
            progress = progress.coerceIn(0f, 1f),
            downloadedMb = downloadedBytes / (1024f * 1024f),
            totalMb = totalBytes / (1024f * 1024f),
            speedMbps = (speedMbps * 10).toInt() / 10f,
            state = state,
            error = error
        )
        _downloadStates.value = _downloadStates.value.toMutableMap().apply { put(modelId, info) }
    }

    private companion object {
        const val TAG = "ModelDownloadManager"
        const val USER_AGENT = "MobileLLM-Android/1.0 (+llama.cpp)"
    }
}
