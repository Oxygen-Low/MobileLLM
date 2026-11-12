package com.example.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val releaseUrl: String
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Checks GitHub Releases for a newer build and installs the APK that the release
 * workflow publishes.
 *
 * The primary lookup uses the GitHub REST API (with ETag revalidation so the
 * once-a-minute poll does not burn through the unauthenticated rate limit). If
 * the API is unavailable, it falls back to the `releases/latest` redirect.
 */
class UpdateManager(private val app: Application) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val redirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var cachedRelease: UpdateInfo? = null
    private var cachedEtag: String? = null
    private var pendingUpdate: UpdateInfo? = null
    private var dismissedVersionCode: Int? = null

    suspend fun checkForUpdates() {
        // Never interrupt an in-flight download or replace an APK that is ready
        // to install; the user has to act on those states first.
        when (_state.value) {
            is UpdateState.Downloading, is UpdateState.ReadyToInstall -> return
            else -> Unit
        }

        // Only show the transient "checking" state when there is nothing on
        // screen. Otherwise the once-a-minute poll would hide and re-show the
        // "update available" / error dialog each time (yes, really).
        val idleStates = _state.value is UpdateState.Idle || _state.value is UpdateState.UpToDate
        if (idleStates) _state.value = UpdateState.Checking

        val latest = fetchLatestRelease()
        if (latest == null) {
            if (idleStates) _state.value = UpdateState.UpToDate
            return
        }

        if (latest.versionCode > BuildConfig.VERSION_CODE &&
            latest.versionCode != dismissedVersionCode
        ) {
            pendingUpdate = latest
            _state.value = UpdateState.Available(latest)
        } else if (idleStates) {
            _state.value = UpdateState.UpToDate
        }
    }

    /** Downloads the pending APK and opens the system installer. */
    suspend fun downloadAndInstall() {
        val info = pendingUpdate ?: return
        if (_state.value is UpdateState.Downloading) return

        // Show progress immediately: this also stops the periodic update check
        // from starting a second download before the first byte arrives.
        _state.value = UpdateState.Downloading(0f)

        try {
            // Blocking network + disk I/O must stay off the main thread, or
            // Android throws NetworkOnMainThreadException (null message) and the
            // user sees the generic "Update download failed" text.
            val apk = withContext(Dispatchers.IO) { downloadApk(info) }
            _state.value = UpdateState.ReadyToInstall(info)
            installApk(apk)
        } catch (e: Exception) {
            _state.value = UpdateState.Error(
                e.message?.takeIf { it.isNotBlank() } ?: "Update download failed (${e.javaClass.simpleName})"
            )
        }
    }

    /** Re-opens the installer for an APK that has already been downloaded. */
    fun install() {
        val info = pendingUpdate ?: return
        val apk = apkFile()
        if (apk.exists()) {
            installApk(apk)
        } else {
            _state.value = UpdateState.Available(info)
        }
    }

    fun dismiss() {
        pendingUpdate?.let { dismissedVersionCode = it.versionCode }
        pendingUpdate = null
        _state.value = UpdateState.Idle
    }

    private suspend fun fetchLatestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(API_LATEST_RELEASE)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
            cachedEtag?.let { builder.header("If-None-Match", it) }

            client.newCall(builder.build()).execute().use { response ->
                when {
                    response.code == 304 -> cachedRelease
                    response.isSuccessful -> {
                        cachedEtag = response.header("ETag")
                        val body = response.body?.string() ?: return@withContext null
                        val info = parseRelease(JSONObject(body))
                        cachedRelease = info
                        info
                    }
                    response.code == 404 -> null
                    // Rate limited or otherwise unavailable: the web redirect has
                    // no API quota and still tells us the newest tag.
                    else -> fetchLatestFromRedirect()
                }
            }
        } catch (e: Exception) {
            fetchLatestFromRedirect()
        }
    }

    private fun fetchLatestFromRedirect(): UpdateInfo? {
        return try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_PAGE)
                .head()
                .header("User-Agent", USER_AGENT)
                .build()

            redirectClient.newCall(request).execute().use { response ->
                val location = response.header("Location") ?: return null
                val tag = location.substringAfterLast("/tag/", "")
                if (tag.isEmpty()) return null
                val versionCode = parseVersionCode(tag) ?: return null

                UpdateInfo(
                    versionCode = versionCode,
                    versionName = tag,
                    releaseNotes = "",
                    apkUrl = apkUrlFor(tag),
                    releaseUrl = releaseUrlFor(tag)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRelease(json: JSONObject): UpdateInfo? {
        val tag = json.optString("tag_name").trim()
        val versionCode = parseVersionCode(tag) ?: return null

        var apkUrl: String? = null
        var fallbackApkUrl: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url")
                if (url.isEmpty()) continue

                if (name.equals(APK_ASSET_NAME, ignoreCase = true)) {
                    apkUrl = url
                    break
                }
                if (fallbackApkUrl == null) fallbackApkUrl = url
            }
        }

        val releaseUrl = json.optString("html_url").ifEmpty { releaseUrlFor(tag) }
        return UpdateInfo(
            versionCode = versionCode,
            versionName = tag,
            releaseNotes = json.optString("body"),
            apkUrl = apkUrl ?: fallbackApkUrl ?: apkUrlFor(tag),
            releaseUrl = releaseUrl
        )
    }

    private fun parseVersionCode(tag: String): Int? {
        val match = TAG_PATTERN.matchEntire(tag.trim()) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun downloadApk(info: UpdateInfo): File {
        val destination = apkFile()
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()

        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        var downloadSize = -1L
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed (HTTP ${response.code})")
            }
            val body = response.body ?: throw IOException("Download returned no data")
            val totalBytes = body.contentLength()
            downloadSize = totalBytes
            var lastPercent = -1

            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        if (totalBytes > 0) {
                            val percent = ((downloaded * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = UpdateState.Downloading(
                                    (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!destination.exists() || destination.length() == 0L) {
            throw IOException("Downloaded APK is empty")
        }
        // Catch truncated transfers before handing a corrupt APK to the installer.
        val actualBytes = destination.length()
        if (downloadSize > 0 && actualBytes != downloadSize) {
            destination.delete()
            throw IOException("Download incomplete ($actualBytes of $downloadSize bytes)")
        }
        return destination
    }

    private fun apkFile(): File = File(File(app.cacheDir, "updates"), "MobileLLM.apk")

    private fun installApk(apk: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !app.packageManager.canRequestPackageInstalls()
            ) {
                // The user must allow this app to install unknown apps before the
                // system installer will accept our APK. The ReadyToInstall state
                // remains so they can tap Install again when they return.
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${app.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(settingsIntent)
                return
            }

            val uri = FileProvider.getUriForFile(
                app,
                "${app.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            _state.value = UpdateState.Error("No app found to install APK updates")
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Could not start the installer")
        }
    }

    private fun apkUrlFor(tag: String) =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tag/$APK_ASSET_NAME"

    private fun releaseUrlFor(tag: String) =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/$tag"

    private companion object {
        const val GITHUB_OWNER = "Oxygen-Low"
        const val GITHUB_REPO = "MobileLLM"
        const val APK_ASSET_NAME = "MobileLLM.apk"
        const val USER_AGENT = "MobileLLM-Updater"

        const val API_LATEST_RELEASE =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        const val LATEST_RELEASE_PAGE =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        val TAG_PATTERN = Regex("^[Vv](\\d+)$")
    }
}
