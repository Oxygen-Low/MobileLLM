package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.example.model.DownloadProgressInfo
import com.example.model.ModelDownloadState

/**
 * Keeps the display awake while the reply is being written or while a model is
 * being downloaded.
 *
 * The timeout is only suspended for work the user is waiting for: browsing the
 * transcript, reading an answer and sitting idle all keep the normal screen
 * timeout, so the phone can still turn itself off and save battery.
 */
@Composable
fun KeepScreenOn(
    generating: Boolean,
    downloads: Collection<DownloadProgressInfo>
) {
    val active = shouldKeepScreenOn(generating, downloads)
    val view = LocalView.current
    // The view flag is tied to the window, so it has to be cleared when the
    // screen leaves the composition (or the condition stops holding) instead
    // of staying on for the rest of the app's life.
    DisposableEffect(view, active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * The decision behind [KeepScreenOn], kept free of Android so it can be tested
 * without a device.
 */
internal fun shouldKeepScreenOn(
    generating: Boolean,
    downloads: Collection<DownloadProgressInfo>
): Boolean = generating || downloads.any { it.state == ModelDownloadState.DOWNLOADING }
