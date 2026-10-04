package com.amiri.videoengine.ui.components

import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.amiri.videoengine.image.ImagePreprocessor
import com.amiri.videoengine.ui.theme.AmiriColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Loads a down-scaled bitmap off the main thread; never decodes full resolution. */
@Composable
fun rememberThumbnail(file: File?, maxSide: Int = 512): ImageBitmap? {
    val state by produceState<ImageBitmap?>(initialValue = null, file, maxSide) {
        value = if (file == null || !file.exists()) {
            null
        } else {
            withContext(Dispatchers.IO) { ImagePreprocessor.loadScaled(file, maxSide)?.asImageBitmap() }
        }
    }
    return state
}

@Composable
fun ImageSlotCard(
    label: String,
    file: File?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val thumb = rememberThumbnail(file, 400)
    Box(
        modifier = modifier
            .aspectRatio(0.78f)
            .clip(shape)
            .background(AmiriColors.Surface)
            .border(BorderStroke(1.dp, if (file != null) AmiriColors.GoldDim else AmiriColors.Outline), shape)
            .clickable(onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(AmiriColors.Background.copy(alpha = 0.75f))
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = AmiriColors.Text, modifier = Modifier.size(16.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = AmiriColors.Text,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(AmiriColors.Background.copy(alpha = 0.6f))
                    .padding(vertical = 4.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = AmiriColors.Gold)
                Spacer(Modifier.height(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = AmiriColors.TextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** Row of selectable pills; used for the few user-facing options. */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = AmiriColors.TextDim)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { opt ->
                val isSel = opt == selected
                val shape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(if (isSel) AmiriColors.Gold.copy(alpha = 0.14f) else AmiriColors.Surface)
                        .border(1.dp, if (isSel) AmiriColors.Gold else AmiriColors.Outline, shape)
                        .clickable { onSelect(opt) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(opt),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSel) AmiriColors.Gold else AmiriColors.Text,
                    )
                }
            }
        }
    }
}

/** Looping video preview using the platform's hardware decoder. */
@Composable
fun LoopingVideo(file: File, aspect: Float, modifier: Modifier = Modifier) {
    val path = file.absolutePath
    key(path) {
        val holder = remember { arrayOfNulls<VideoView>(1) }
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        start()
                    }
                    setOnClickListener { if (isPlaying) pause() else start() }
                    setVideoPath(path)
                    holder[0] = this
                }
            },
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.3f, 3f))
                .clip(RoundedCornerShape(16.dp))
                .background(AmiriColors.Surface),
        )
        DisposableEffect(Unit) {
            onDispose { holder[0]?.stopPlayback() }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = AmiriColors.Gold,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
