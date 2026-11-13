package com.joaopat98.instagrammediadownloader

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore

class GalleryFixture : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val code = try {
            val resolver = targetContext.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "souvenir-gallery-fixture.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Souvenir")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(collection, values))
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.MAGENTA)
                checkNotNull(resolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) == 1)
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            } finally {
                bitmap.recycle()
            }
            result.putString("fixture", "saved")
            Activity.RESULT_OK
        } catch (error: Exception) {
            result.putString("error", error.toString())
            Activity.RESULT_CANCELED
        }
        finish(code, result)
    }
}
