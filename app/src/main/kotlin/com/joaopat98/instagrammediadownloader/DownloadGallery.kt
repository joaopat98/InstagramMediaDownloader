package com.joaopat98.instagrammediadownloader

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.IOException
import java.util.Locale

internal object DownloadGallery {
    data class Item(val name: String, val uri: Uri, val mime: String, val savedAt: Long)

    private val folders = setOf("Souvenir", "InstagramMediaDownloader")
        .map { "${Environment.DIRECTORY_DOWNLOADS}/$it/" }

    fun read(context: Context): List<Item> {
        val items = linkedMapOf<String, Item>()
        val resolver = context.contentResolver
        val store = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED)
        // MediaStore also includes streamed Stories written without DownloadManager.
        val selection = "relative_path IN (?, ?) AND is_pending = 0" +
            if (Build.VERSION.SDK_INT >= 30) " AND is_trashed = 0" else ""
        resolver.query(store, projection, selection, folders.toTypedArray(), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val mime = mediaType(name, cursor.getString(2)) ?: continue
                items[name] = Item(name, ContentUris.withAppendedId(store, cursor.getLong(0)),
                    mime, cursor.getLong(3) * 1000)
            }
        }

        // Older DownloadManager entries can have a different MediaStore owner.
        val manager = context.getSystemService(DownloadManager::class.java)
        manager?.query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL))?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val local = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            val type = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
            val date = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            while (cursor.moveToNext()) {
                val path = cursor.getString(local)?.let(Uri::parse)?.path ?: continue
                if (folders.none { path.substringBeforeLast('/').endsWith("/${it.trimEnd('/')}") }) continue
                val name = path.substringAfterLast('/')
                val mime = mediaType(name, cursor.getString(type)) ?: continue
                val uri = manager.getUriForDownloadedFile(cursor.getLong(id)) ?: continue
                items.putIfAbsent(name, Item(name, uri, mime, cursor.getLong(date)))
            }
        }
        return items.values.sortedWith(compareByDescending<Item> { it.savedAt }.thenBy { it.name })
    }

    fun thumbnail(context: Context, item: Item, size: Size): Bitmap? {
        val resolver = context.contentResolver
        return try {
            if (item.mime.startsWith("video/")) {
                MediaMetadataRetriever().use { reader ->
                    reader.setDataSource(context, item.uri)
                    reader.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size.width, size.height)
                }
            } else {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
                    val ratio = maxOf(1f, info.size.width.toFloat() / size.width, info.size.height.toFloat() / size.height)
                    decoder.setTargetSize((info.size.width / ratio).toInt().coerceAtLeast(1),
                        (info.size.height / ratio).toInt().coerceAtLeast(1))
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun mediaType(name: String, mime: String?): String? {
        if (mime?.startsWith("image/") == true || mime?.startsWith("video/") == true) return mime
        return when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif" -> "image/avif"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> null
        }
    }
}
