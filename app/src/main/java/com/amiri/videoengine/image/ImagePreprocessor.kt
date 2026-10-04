package com.amiri.videoengine.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.webkit.MimeTypeMap
import com.amiri.videoengine.ai.model.AspectRatio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Image handling that never touches the original:
 *  - originals are copied byte-for-byte into app storage,
 *  - provider copies are decoded down-sampled (no full-size bitmaps), EXIF-rotated,
 *    center-cropped to the chosen aspect ratio, resized and re-encoded once
 *    (which also strips location/camera metadata), then cached.
 */
class ImagePreprocessor(private val context: Context) {

    private val cacheDir: File get() = File(context.cacheDir, "processed").apply { mkdirs() }

    /** Copies a picked image into [dir] as an untouched original. */
    suspend fun importOriginal(uri: Uri, dir: File, baseName: String): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        if (!mime.startsWith("image/")) throw IOException("Not an image")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        dir.mkdirs()
        dir.listFiles()?.filter { it.name.startsWith("$baseName.") }?.forEach { it.delete() }
        val out = File(dir, "$baseName.$ext")
        resolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("Cannot open image")
        // Validate that Android can actually decode it.
        imageSize(out) ?: run {
            out.delete()
            throw IOException("Unsupported image format")
        }
        out
    }

    /** Width/height without decoding pixels. */
    fun imageSize(file: File): Pair<Int, Int>? = try {
        var size: Pair<Int, Int>? = null
        val src = ImageDecoder.createSource(file)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            size = info.size.width to info.size.height
            // Decode at tiny size; we only wanted the header.
            decoder.setTargetSize(1, 1)
        }.recycle()
        size
    } catch (_: Exception) {
        null
    }

    /** Provider-ready JPEG: cropped to [aspect], long side <= [maxLongSide]. Cached by content. */
    suspend fun prepareForProvider(original: File, aspect: AspectRatio, maxLongSide: Int = 1280): File =
        withContext(Dispatchers.IO) {
            val key = sha1("${original.absolutePath}|${original.length()}|${original.lastModified()}|${aspect.name}|$maxLongSide")
            val out = File(cacheDir, "$key.jpg")
            if (out.exists() && out.length() > 0) return@withContext out

            val (srcW, srcH) = imageSize(original) ?: throw IOException("Unreadable image")
            // Down-sample at decode time so we never hold a huge bitmap.
            val decodeScale = max(1f, max(srcW, srcH) / (maxLongSide * 1.5f))
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(original)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize((srcW / decodeScale).roundToInt().coerceAtLeast(1), (srcH / decodeScale).roundToInt().coerceAtLeast(1))
            }
            val cropped = centerCrop(bitmap, aspect.ratio)
            val scale = minOf(1f, maxLongSide.toFloat() / max(cropped.width, cropped.height))
            val tw = ((cropped.width * scale).roundToInt() / 16 * 16).coerceAtLeast(16)
            val th = ((cropped.height * scale).roundToInt() / 16 * 16).coerceAtLeast(16)
            val finalBmp = if (tw != cropped.width || th != cropped.height) {
                Bitmap.createScaledBitmap(cropped, tw, th, true)
            } else {
                cropped
            }
            val tmp = File(cacheDir, "$key.tmp")
            tmp.outputStream().use { finalBmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
            tmp.renameTo(out)
            if (finalBmp !== cropped) finalBmp.recycle()
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
            out
        }

    /** Small JPEG thumbnail for lists. */
    suspend fun thumbnail(source: File, out: File, maxSide: Int = 384): File? = withContext(Dispatchers.IO) {
        try {
            val bmp = loadScaled(source, maxSide) ?: return@withContext null
            out.parentFile?.mkdirs()
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun centerCrop(src: Bitmap, targetRatio: Float): Bitmap {
        val srcRatio = src.width.toFloat() / src.height
        if (kotlin.math.abs(srcRatio - targetRatio) < 0.01f) return src
        return if (srcRatio > targetRatio) {
            val w = (src.height * targetRatio).roundToInt()
            Bitmap.createBitmap(src, (src.width - w) / 2, 0, w, src.height)
        } else {
            val h = (src.width / targetRatio).roundToInt()
            Bitmap.createBitmap(src, 0, (src.height - h) / 2, src.width, h)
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** Decodes an image file scaled so its long side is <= [maxSide]. */
        fun loadScaled(file: File, maxSide: Int): Bitmap? = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val s = max(1f, max(w, h).toFloat() / maxSide)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize((w / s).roundToInt().coerceAtLeast(1), (h / s).roundToInt().coerceAtLeast(1))
            }
        } catch (_: Exception) {
            null
        }
    }
}
