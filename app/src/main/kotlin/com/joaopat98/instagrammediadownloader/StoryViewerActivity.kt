package com.joaopat98.instagrammediadownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

internal class StoryViewerActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var targetPath: String
    private lateinit var save: Button
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = InstagramMedia.findLink(intent.getStringExtra(EXTRA))
        if (link?.kind != "story" || !InstagramLoginActivity.hasSession()) {
            finish()
            return
        }
        targetPath = link.uri.path.trimEnd('/')
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme != "https" || request.url.host !in instagramHosts

            }
        }
        save = Button(this).apply {
            text = "Save displayed Story"
            setOnClickListener { saveMedia(link.id) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(save)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                web, storyVideoProbe(link.id, targetPath) + ";\n" + storySegmentProbe(targetPath),
                setOf("https://www.instagram.com")
            )
        }
        web.loadUrl(link.uri.toString())
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            setContentView(android.view.View(this))
            web.destroy()
        }
        super.onDestroy()
    }

    private fun saveMedia(storyId: String) {
        save.isEnabled = false
        web.evaluateJavascript(viewerProbe()) { result ->
            if (isDestroyed) return@evaluateJavascript
            val media = JSONTokener(result ?: "null").nextValue() as? JSONObject
            val source = media?.optString("source")?.takeIf(String::isNotBlank)
            val video = media?.optBoolean("video") == true
            if (video && source == null) {
                save.isEnabled = false
                save.text = "Waiting for the complete Story stream…"
                waitForSegments(0)
                return@evaluateJavascript
            }
            val uri = source?.let(Uri::parse)
            val host = uri?.host.orEmpty()
            if (uri?.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
                !(host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net")) ||
                (video && (!uri.path.orEmpty().endsWith(".mp4", ignoreCase = true) ||
                    uri.queryParameterNames.any { it in setOf("bytestart", "byteend", "range") }))) {
                save.text = "No complete media file found. Tap to retry"
                save.isEnabled = true
                return@evaluateJavascript
            }
            val manager = getSystemService(DownloadManager::class.java)
            val extension = uri.path.orEmpty().substringAfterLast('.').lowercase()
            val suffix = if (video) "mp4" else extension.takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
            val filename = "Instagram_${storyId}_${System.currentTimeMillis()}.$suffix"
            try {
                checkNotNull(manager)
                val id = manager.enqueue(DownloadManager.Request(uri)
                    .setTitle(filename)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Souvenir/$filename")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED))
                save.isEnabled = false
                save.text = "Saving Story…"
                checkDownload(manager, id, video, 0)
            } catch (_: RuntimeException) {
                save.text = "Could not queue Story. Tap to retry"
                save.isEnabled = true
            }
        }
    }

    private fun checkDownload(manager: DownloadManager, id: Long, video: Boolean, attempt: Int) {
        if (isDestroyed) return
        val record = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) null else
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        }
        when {
            record?.first == DownloadManager.STATUS_SUCCESSFUL -> {
                if (video) {
                    verifyVideo(manager, id)
                    return
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                manager.openDownloadedFile(id).use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds) }
                save.text = "Story photo saved to Downloads"
            }
            record?.first == DownloadManager.STATUS_FAILED || attempt >= 30 -> {
                save.text = "Could not save Story. Tap to retry"
                save.isEnabled = true
            }
            else -> web.postDelayed({ checkDownload(manager, id, video, attempt + 1) }, 1_000)
        }
    }

    private fun verifyVideo(manager: DownloadManager, id: Long) {
        val videoUri = manager.getUriForDownloadedFile(id)
        try {
            checkNotNull(videoUri)
            MediaMetadataRetriever().use { metadata ->
                metadata.setDataSource(this, videoUri)
                val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val hasVideo = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                check(hasVideo && duration != null && duration > 0)
                save.text = "Story video saved to Downloads"
            }
        } catch (_: Exception) {
            save.text = "Saved file did not verify as video"
            save.isEnabled = true
        }
    }

    private fun waitForSegments(attempt: Int) {
        if (isDestroyed) return
        web.evaluateJavascript("window.souvenirFreezeStory?.() || null") { raw ->
            val result = JSONTokener(raw ?: "null").nextValue() as? JSONObject
            val state = result?.optString("state").orEmpty()
            if (state == "ready" && result != null) {
                val tracks = result.optJSONArray("tracks")
                val sizes = (0 until (tracks?.length() ?: 0)).map { index ->
                    tracks!!.getJSONObject(index).optInt("size")
                }
                val duration = result.optLong("durationMs")
                if (sizes.isEmpty() || sizes.any { it <= 0 } ||
                    sizes.sumOf(Int::toLong) > 64 * 1024 * 1024 || duration <= 0) {
                    segmentFailure()
                    return@evaluateJavascript
                }
                val folder = kotlin.io.path.createTempDirectory(cacheDir.toPath(), "story-segments-").toFile()
                save.text = "Combining Story tracks…"
                transferSegment(folder, sizes, duration, 0, 0)
            } else if (attempt < 40 && state in setOf("buffering", "waiting-for-video", "no-captured-tracks")) {
                web.postDelayed({ waitForSegments(attempt + 1) }, 750)
            } else {
                segmentFailure()
            }
        }
    }

    private fun transferSegment(folder: File, sizes: List<Int>, duration: Long, index: Int, offset: Int) {
        if (isDestroyed) {
            folder.deleteRecursively()
            return
        }
        if (index == sizes.size) {
            Thread {
                try {
                    val files = sizes.indices.map { File(folder, "track-$it.mp4") }
                    StorySegmentMuxer.save(applicationContext, files, folder, duration)
                    runOnUiThread {
                        if (!isDestroyed) save.text = "Story video saved to Downloads"
                    }
                } catch (_: Exception) {
                    runOnUiThread { if (!isDestroyed) segmentFailure() }
                } finally {
                    folder.deleteRecursively()
                }
            }.start()
            return
        }
        val length = minOf(131_072, sizes[index] - offset)
        web.evaluateJavascript("window.souvenirReadStoryBytes?.($index,$offset,$length) || null") { raw ->
            try {
                val encoded = JSONTokener(raw ?: "null").nextValue() as? String
                val bytes = Base64.decode(checkNotNull(encoded), Base64.DEFAULT)
                check(bytes.size == length)
                File(folder, "track-$index.mp4").appendBytes(bytes)
                if (offset + length == sizes[index]) transferSegment(folder, sizes, duration, index + 1, 0)
                else transferSegment(folder, sizes, duration, index, offset + length)
            } catch (_: Exception) {
                folder.deleteRecursively()
                if (!isDestroyed) segmentFailure()
            }
        }
    }

    private fun segmentFailure() {
        save.text = "Could not combine the Story stream. Tap to retry"
        save.isEnabled = true
    }

    private fun viewerProbe() = """
        (() => {
          if (location.hostname !== 'www.instagram.com' ||
              location.pathname.replace(/\/$/, '') !== ${JSONObject.quote(targetPath)}) return null;
          const visible = node => {
            const bounds = node.getBoundingClientRect();
            return bounds.width >= 160 && bounds.height >= 160 &&
              bounds.bottom > 0 && bounds.top < innerHeight && bounds.right > 0 && bounds.left < innerWidth &&
              getComputedStyle(node).visibility === 'visible';
          };
          const videos = Array.from(document.querySelectorAll('video')).filter(visible);
          const images = Array.from(document.images).filter(image => visible(image) && image.complete &&
            image.naturalWidth >= 240 && image.naturalHeight >= 240);
          const probe = window.souvenirVideoProbe?.() || {};
            if (videos.length === 1) {
              const source = videos[0].currentSrc || videos[0].src;
              return {source: probe.candidate || (source.startsWith('https:') ? source : ''), video: true};
            }
            return videos.length === 0 && images.length === 1
              ? {source: images[0].currentSrc || images[0].src, video: false} : null;
        })()
    """.trimIndent()

    companion object {
        const val EXTRA = "story"
        private val instagramHosts = setOf("www.instagram.com", "instagram.com", "accountscenter.instagram.com")
    }
}
