package com.amiri.videoengine.video

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class VideoInfo(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val mime: String?,
)

/**
 * Local video work. Engines already return H.264 MP4, so the file is kept as-is
 * (no re-encoding = no quality loss, no battery drain). This class reads its
 * metadata, makes thumbnails, extracts frames, saves to the gallery and shares.
 */
class VideoPostProcessor(private val context: Context) {

    suspend fun inspect(file: File): VideoInfo? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = w; w = h; h = t
            }
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            if (w == 0 || h == 0) null else VideoInfo(w, h, d, mime)
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Saves one frame as JPEG. [atMs] < 0 means the last frame. */
    suspend fun extractFrame(video: File, out: File, atMs: Long, maxSide: Int = 0): File? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(video.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val t = if (atMs < 0) (dur - 50).coerceAtLeast(0) else atMs.coerceAtMost(dur)
            val frame = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                ?: return@withContext null
            val bmp = if (maxSide > 0 && maxOf(frame.width, frame.height) > maxSide) {
                val s = maxSide.toFloat() / maxOf(frame.width, frame.height)
                Bitmap.createScaledBitmap(frame, (frame.width * s).toInt().coerceAtLeast(1), (frame.height * s).toInt().coerceAtLeast(1), true)
            } else {
                frame
            }
            out.parentFile?.mkdirs()
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (bmp !== frame) bmp.recycle()
            frame.recycle()
            out
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Copies the video into Movies/Amiri Video Engine (visible in the Gallery). No permission needed. */
    suspend fun saveToGallery(video: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Amiri Video Engine")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Gallery not available")
        try {
            resolver.openOutputStream(uri)?.use { out -> video.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Cannot write to gallery")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun shareIntent(video: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", video)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
