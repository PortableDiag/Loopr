package com.loopr.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads and caches video thumbnails off the main thread. */
object ThumbnailLoader {
    private val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cache = object : LruCache<Long, Bitmap>(maxMem / 8) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }

    fun cached(id: Long): Bitmap? = cache.get(id)

    suspend fun load(context: Context, item: VideoItem): Bitmap? {
        cache.get(item.id)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(item.uri, Size(384, 216), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver, item.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                }
            } catch (e: Throwable) {
                null
            }
        }
        if (bmp != null) cache.put(item.id, bmp)
        return bmp
    }
}
