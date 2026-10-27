package com.loopr.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * The library as the grid shows it: every video MediaStore knows, newest first.
 *
 * One query for the grid and the local API both, so an agent listing the library sees exactly what
 * the person scrolling it sees, in the same order — which matters, because opening a video from the
 * library queues the whole list in that order.
 */
object VideoLibrary {

    /** A library video and the folder it sits in. The folder id tells two "Camera" folders apart. */
    class Entry(val item: VideoItem, val folderId: Long, val path: String?)

    val collection: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * True under "Allow limited access" (Android 14+): the video permission still reads as granted,
     * but MediaStore shows only the handful of videos the person picked.
     */
    fun hasLimitedAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED

    fun query(context: Context): List<Entry> = query(context, null, null)

    /** The library video at [path], if MediaStore has indexed it. */
    fun findByPath(context: Context, path: String): Entry? =
        query(context, "${MediaStore.Video.Media.DATA} = ?", arrayOf(path)).firstOrNull()

    @Suppress("DEPRECATION") // DATA is still the only way to match a path the caller already has.
    private fun query(context: Context, selection: String?, args: Array<String>?): List<Entry> {
        val list = ArrayList<Entry>()
        val collection = collection
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.DATA
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val item = VideoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(nameCol) ?: "Video",
                    durationMs = c.getLong(durCol),
                    sizeBytes = c.getLong(sizeCol),
                    width = c.getInt(wCol),
                    height = c.getInt(hCol),
                    bucket = c.getString(bucketCol) ?: ""
                )
                list.add(Entry(item, c.getLong(bucketIdCol), c.getString(dataCol)))
            }
        }
        return list
    }
}
