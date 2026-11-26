package com.zero7goat.taz.features.files.presentation

import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.files.data.FileEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays an audio or video file inside TAZ, with Android's own player controls. The file is played
 * from TAZ's private (decrypted, temporary) viewing copy; nothing is handed to another app.
 */
@Composable
fun MediaPlayerScreen(file: FileEntity, audioOnly: Boolean, resolveBlob: (FileEntity) -> File?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val blob by produceState<File?>(null, file.id) { value = withContext(Dispatchers.IO) { resolveBlob(file) } }
    val controller = remember { MediaController(context) }
    var player: VideoView? = null
    DisposableEffect(Unit) { onDispose { player?.stopPlayback(); controller.hide() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val source = blob
        if (source == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setMediaController(controller)
                        controller.setAnchorView(this)
                        setVideoPath(source.absolutePath)
                        setOnPreparedListener { start(); controller.show(0) }
                        setOnErrorListener { _, _, _ -> true }
                        player = this
                    }
                },
                modifier = Modifier.align(Alignment.Center).fillMaxSize()
            )
        }
        if (audioOnly) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(TazIcons.File, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                Text(file.displayName, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp)) {
            Icon(TazIcons.Close, contentDescription = "Close player", tint = Color.White)
        }
    }
}
