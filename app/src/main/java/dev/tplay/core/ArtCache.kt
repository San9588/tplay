package dev.tplay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ArtCache(
    private val appContext: Context,
    private val okHttpClient: okhttp3.OkHttpClient,
) {

    private val memCache = object : android.util.LruCache<String, Bitmap>(memCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val fileCache = FileCache(appContext)

    private fun memCacheSizeKb(): Int {
        val maxMemKb = Runtime.getRuntime().maxMemory() / 1024
        return (maxMemKb / 10).toInt().coerceIn(4 * 1024, 24 * 1024)
    }

    suspend fun loadArtAsync(
        uri: Uri?,
        key: String,
        maxSize: Int,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): Bitmap? = withContext(dispatcher) {
        if (uri == null) return@withContext null
        memCache.get(key)?.let { return@withContext it }
        fileCache.get(key)?.let {
            val scaled = downscale(it, maxSize)
            memCache.put(key, scaled)
            return@withContext scaled
        }
        val loaded = decodeArt(uri, maxSize) ?: return@withContext null
        memCache.put(key, loaded)
        fileCache.put(key, loaded)
        loaded
    }

    suspend fun loadFromUrl(
        url: String,
        key: String,
        maxSize: Int,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Bitmap? = withContext(dispatcher) {
        memCache.get(key)?.let { return@withContext it }
        fileCache.get(key)?.let {
            val scaled = downscale(it, maxSize)
            memCache.put(key, scaled)
            return@withContext scaled
        }
        val loaded = runCatching {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", dev.tplay.data.youtube.OkHttpDownloader.USER_AGENT)
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val bytes = resp.body?.bytes() ?: return@runCatching null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull() ?: return@withContext null
        val scaled = downscale(loaded, maxSize)
        memCache.put(key, scaled)
        fileCache.put(key, scaled)
        scaled
    }

    suspend fun toAsciiAsync(
        key: String,
        bitmap: Bitmap,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): ImageBitmap = withContext(dispatcher) {
        val asciiKey = "ascii:$key"
        memCache.get(asciiKey)?.let { return@withContext it.asImageBitmap() }
        val ascii = bitmap.toAsciiBitmap(appContext)
        memCache.put(asciiKey, ascii)
        ascii.asImageBitmap()
    }

    suspend fun placeholderAsync(
        key: String,
        seed: Long,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): ImageBitmap = withContext(dispatcher) {
        val phKey = "ph:$seed"
        memCache.get(phKey)?.let { return@withContext it.asImageBitmap() }
        val ph = generateAsciiPlaceholder(appContext, seed)
        memCache.put(phKey, ph)
        ph.asImageBitmap()
    }

    private fun decodeArt(uri: Uri, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize &&
            bounds.outHeight / (sample * 2) >= maxSize
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.getOrNull()
    }

    private fun downscale(bitmap: Bitmap, maxSize: Int): Bitmap {
        if (bitmap.width <= maxSize && bitmap.height <= maxSize) return bitmap
        val scale = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true,
        )
    }

    private class FileCache(context: Context) {
        private val dir = context.cacheDir.resolve("art").apply { mkdirs() }

        @Synchronized
        fun get(key: String): Bitmap? {
            val file = dir.resolve("$key.webp")
            if (!file.exists()) return null
            return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }

        @Synchronized
        fun put(key: String, bitmap: Bitmap) {
            runCatching {
                dir.resolve("$key.webp").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it)
                }
            }
        }
    }
}

fun mediaStoreArt(context: Context, albumId: Long, maxSize: Int): Bitmap? {
    if (albumId <= 0) return null
    val artUri = Uri.withAppendedPath(
        Uri.parse("content://media/external/audio/albumart"),
        albumId.toString(),
    )
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(artUri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(artUri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()
}
