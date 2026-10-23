package app.termchat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Immutable
import app.termchat.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

@Immutable
data class ImageInfo(val path: String, val width: Int, val height: Int, val bytes: Long) {
    val label: String get() = "[img ${width}x$height ${(bytes + 1023) / 1024} KB]"
}

class ImageStore(private val context: Context) {
    private val dir by lazy { File(context.filesDir, "images") }
    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun save(uri: Uri): ImageInfo = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(uri)
        dir.mkdirs()
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        val info = ImageInfo(file.absolutePath, bitmap.width, bitmap.height, file.length())
        bitmap.recycle()
        info
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) { File(path).delete() }

    fun info(path: String): ImageInfo {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return ImageInfo(path, options.outWidth, options.outHeight, File(path).length())
    }

    suspend fun thumbnail(path: String, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        thumbnails.get(path) ?: run {
            val bounds = info(path)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.width, bounds.height, maxSide)
            }
            BitmapFactory.decodeFile(path, options)?.also { thumbnails.put(path, it) }
        }
    }

    private fun decodeScaled(uri: Uri): Bitmap {
        val side = Config.IMAGE_MAX_SIDE
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = side.toFloat() / max(info.size.width, info.size.height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, side)
            }
            val sampled = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                ?: error("unsupported image")
            val scale = side.toFloat() / max(sampled.width, sampled.height)
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    sampled,
                    (sampled.width * scale).roundToInt(),
                    (sampled.height * scale).roundToInt(),
                    true,
                )
            } else {
                sampled
            }
        }
        return decoded
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private companion object {
        const val JPEG_QUALITY = 85
        const val THUMBNAIL_CACHE_BYTES = 8 * 1024 * 1024
    }
}
