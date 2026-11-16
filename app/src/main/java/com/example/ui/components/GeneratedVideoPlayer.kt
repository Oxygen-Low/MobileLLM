package com.example.ui.components

import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TelemetryCyan
import java.io.File

/**
 * Plays a generated clip with its soundtrack.
 *
 * The clip is an MP4 with an AAC track, so the platform's own VideoView is the
 * player: it handles the codecs, the audio routing and the surface without the
 * app bundling a media library. The overlay button is there because the clip
 * starts paused - a transcript with several clips would otherwise talk over
 * itself.
 */
@Composable
fun GeneratedVideoPlayer(
    file: File,
    id: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var videoView by remember(file.path, file.lastModified()) { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember(file.path, file.lastModified()) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .testTag("generated_video_$id")
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoPath(file.absolutePath)
                    setOnPreparedListener { player ->
                        player.isLooping = true
                        // A muted first frame is a useful poster for the clip.
                        seekTo(1)
                    }
                    setOnCompletionListener { isPlaying = false }
                    setOnErrorListener { _, _, _ ->
                        isPlaying = false
                        true
                    }
                    videoView = this
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )

        IconButton(
            onClick = {
                val player = videoView ?: return@IconButton
                if (player.isPlaying) {
                    player.pause()
                    isPlaying = false
                } else {
                    player.start()
                    isPlaying = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .size(42.dp)
                .background(SurfaceDark.copy(alpha = 0.72f), CircleShape)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause the clip" else "Play the clip",
                tint = TelemetryCyan,
                modifier = Modifier.size(24.dp)
            )
        }

        Text(
            text = "VIDEO • SOUND",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            ),
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(SurfaceDark.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }

    // The view owns a media player, so it has to be told to let go when the
    // message scrolls out of the transcript or the chat is closed.
    DisposableEffect(file.path) {
        onDispose {
            videoView?.stopPlayback()
            videoView = null
        }
    }
}