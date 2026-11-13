package com.joaopat98.instagrammediadownloader

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.util.Size
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var download: Button
    private lateinit var status: TextView
    private lateinit var signIn: Button
    private lateinit var state: DownloadState
    private lateinit var gallery: LinearLayout
    private var galleryItems: List<DownloadGallery.Item>? = null
    private var galleryLimit = 12
    private var galleryRequest = 0
    private val refreshGallery = Runnable { loadGallery() }
    private val galleryObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = scheduleGallery()
    }
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = scheduleGallery()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        contentResolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, galleryObserver)
        scheduleGallery()
    }

    override fun onStop() {
        unregisterReceiver(downloadReceiver)
        contentResolver.unregisterContentObserver(galleryObserver)
        main.removeCallbacks(refreshGallery)
        galleryRequest++
        super.onStop()
    }

    private fun scheduleGallery() {
        main.removeCallbacks(refreshGallery)
        main.postDelayed(refreshGallery, 250)
    }

    private fun loadGallery() {
        val request = ++galleryRequest
        val context = applicationContext
        galleryWorker.execute {
            val items = try {
                DownloadGallery.read(context)
            } catch (_: RuntimeException) {
                null
            }
            main.post {
                if (request != galleryRequest || isDestroyed) return@post
                if (items != galleryItems || gallery.childCount == 0) {
                    galleryItems = items
                    renderGallery()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = lastNonConfigurationInstance as? DownloadState ?: DownloadState()
        buildScreen()
        state.observer = ::render
        render()
        if (savedInstanceState == null) acceptShare(intent)
        else input.setText(savedInstanceState.getString("link", ""))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SIGN_IN && resultCode == RESULT_OK) {
            state.retryLink?.let { submit(it.toString(), shared = true) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("link", input.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onRetainNonConfigurationInstance(): Any = state

    override fun onDestroy() {
        state.observer = null
        super.onDestroy()
    }

    private fun acceptShare(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) {
            submit(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(), shared = true)
        }
    }

    private fun submit(text: String?, shared: Boolean = false) {
        val link = InstagramMedia.findLink(text)
        if (link == null) {
            state.update(getString(R.string.invalid_link), error = true)
            return
        }
        input.setText(link.uri.toString())
        input.setSelection(input.length())
        if (link.kind == "story" && InstagramLoginActivity.hasSession()) {
            startActivity(Intent(this, StoryViewerActivity::class.java)
                .putExtra(StoryViewerActivity.EXTRA, link.uri.toString()))
            return
        }
        if (!shared) {
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        }

        val pending = state
        if (!pending.active.add(link.uri)) return
        pending.retryLink = null
        pending.update(getString(R.string.finding_media))
        val app = applicationContext
        worker.execute {
            val result = try {
                queueDownloads(app, link)
            } catch (_: InstagramMedia.StorySignInRequired) {
                Result(app.getString(R.string.story_sign_in_required), error = true, retryLink = link.uri)
            } catch (_: InstagramMedia.Unavailable) {
                Result(app.getString(R.string.media_unavailable), error = true, retryLink = link.uri)
            } catch (error: IOException) {
                val detail = error.message?.takeIf { it.startsWith("Instagram ") }
                Result(detail ?: app.getString(R.string.network_error), error = true)
            } catch (_: RuntimeException) {
                Result(app.getString(R.string.queue_error), error = true)
            }
            main.post {
                pending.active.remove(link.uri)
                pending.retryLink = result.retryLink
                val message = if (pending.active.isEmpty()) result.message
                    else app.getString(R.string.more_links, result.message)
                pending.update(message, result.error)
            }
        }
    }

    private fun render() {
        download.isEnabled = state.active.isEmpty()
        download.alpha = if (download.isEnabled) 1f else 0.65f
        download.setText(if (download.isEnabled) R.string.download else R.string.finding_media)
        status.setTextColor(if (state.error) errorColor else muted)
        status.text = state.message
        status.visibility = if (state.message.isEmpty()) View.GONE else View.VISIBLE
        signIn.visibility = if (state.retryLink != null && state.active.isEmpty() &&
            !InstagramLoginActivity.hasSession()) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION")
    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(paper)
            isFillViewport = true
        }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(appearance, appearance)
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.ime())
                view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
                WindowInsets.CONSUMED
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.statusBarColor = paper
        window.navigationBarColor = paper

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(28))
            isFocusableInTouchMode = true
        }
        scroll.addView(content)
        content.addView(label(getString(R.string.app_name), 34f, ink).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAccessibilityHeading = true
        })
        content.add(label(getString(R.string.instructions), 16f, muted), 12)

        input = EditText(this).apply {
            setHint(R.string.link_hint)
            setTextColor(ink)
            setHintTextColor(muted)
            textSize = 16f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = surface(Color.WHITE, Color.rgb(224, 193, 219))
            setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_GO || !download.isEnabled) false
                else {
                    submit(text.toString())
                    true
                }
            }
        }
        content.add(input, 28)

        download = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            minHeight = dp(52)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(100, 48, 182), Color.rgb(163, 32, 119), Color.rgb(184, 48, 83))).apply {
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { submit(input.text.toString()) }
        }
        content.add(download, 12)

        status = label("", 15f, muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.add(status, 16)

        signIn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            setText(R.string.sign_in)
            setTextColor(accent)
            setOnClickListener {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this@MainActivity, InstagramLoginActivity::class.java), SIGN_IN)
            }
        }
        content.add(signIn, 8)

        content.add(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            setText(R.string.open_downloads)
            setTextColor(accent)
            setOnClickListener {
                try {
                    startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                } catch (_: ActivityNotFoundException) {
                    state.update(getString(R.string.open_files))
                }
            }
        }, 12)

        gallery = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.add(gallery, 20)
        content.requestFocus()
    }

    private fun renderGallery() {
        gallery.removeAllViews()
        gallery.addView(label(getString(R.string.saved_media), 22f, ink).apply {
            isAccessibilityHeading = true
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        val items = galleryItems
        if (items.isNullOrEmpty()) {
            val message = if (items == null) R.string.gallery_unavailable else R.string.gallery_empty
            gallery.add(label(getString(message), 15f, muted), 8)
            return
        }
        for (pair in items.take(galleryLimit).chunked(2)) {
            val row = LinearLayout(this)
            for ((index, item) in pair.withIndex()) {
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = surface(Color.WHITE, Color.rgb(237, 213, 232))
                    clipToOutline = true
                    isFocusable = true
                    isClickable = true
                    foreground = android.util.TypedValue().let {
                        theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                        getDrawable(it.resourceId)
                    }
                    setOnClickListener { openMedia(item) }
                }
                val video = item.mime.startsWith("video/")
                tile.contentDescription = getString(if (video) R.string.open_saved_video else R.string.open_saved_photo, item.name)
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.rgb(250, 232, 245))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                tile.addView(image, LinearLayout.LayoutParams(-1, dp(138)))
                tile.addView(label(getString(if (video) R.string.saved_video else R.string.saved_photo), 14f, accent).apply {
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = dp(10)
                })
                val context = applicationContext
                val size = Size(dp(320), dp(240))
                galleryWorker.execute {
                    val preview = DownloadGallery.thumbnail(context, item, size)
                    main.post { if (image.isAttachedToWindow) image.setImageBitmap(preview) }
                }
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply {
                marginStart = dp(10)
            })
            gallery.add(row, 10)
        }
        if (galleryLimit < items.size) {
            gallery.add(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                isAllCaps = false
                setText(R.string.gallery_show_more)
                setTextColor(accent)
                setOnClickListener {
                    galleryLimit += 12
                    renderGallery()
                }
            }, 8)
        }
    }

    private fun openMedia(item: DownloadGallery.Item) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mime)
            clipData = ClipData.newRawUri(item.name, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(view, getString(R.string.open_media_with)))
        } catch (_: ActivityNotFoundException) {
            state.update(getString(R.string.media_viewer_unavailable), error = true)
        }
    }

    private fun surface(color: Int, border: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), border)
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun LinearLayout.add(child: View, margin: Int) {
        addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(margin) })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private class DownloadState {
        val active = mutableSetOf<URI>()
        var message = ""
        var error = false
        var observer: (() -> Unit)? = null
        var retryLink: URI? = null

        fun update(message: String, error: Boolean = false) {
            this.message = message
            this.error = error
            observer?.invoke()
        }
    }

    private data class Result(val message: String, val error: Boolean = false, val retryLink: URI? = null)

    private companion object {
        const val SIGN_IN = 1
        val paper = Color.rgb(255, 247, 252)
        val ink = Color.rgb(47, 24, 56)
        val muted = Color.rgb(105, 73, 108)
        val accent = Color.rgb(130, 35, 123)
        val errorColor = Color.rgb(150, 40, 34)
        val worker = Executors.newSingleThreadExecutor()
        val galleryWorker = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())

        fun queueDownloads(context: Context, link: InstagramMedia.Link): Result {
            val assets = InstagramMedia.resolve(link, CookieManager.getInstance()::getCookie)
            val manager = checkNotNull(context.getSystemService(DownloadManager::class.java))
            val batch = UUID.randomUUID().toString().take(8)
            for ((index, asset) in assets.withIndex()) {
                val uri = Uri.parse(asset.url)
                val suffix = extension(asset, uri)
                val filename = "Instagram_${link.id}_${batch}_${index + 1}$suffix"
                try {
                    manager.enqueue(DownloadManager.Request(uri)
                        .setTitle(filename)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                            "Souvenir/$filename"))
                } catch (error: RuntimeException) {
                    if (index == 0) throw error
                    return Result(context.getString(R.string.partial_queue, index, assets.size), error = true)
                }
            }
            return Result(context.resources.getQuantityString(R.plurals.queued, assets.size, assets.size))
        }

        fun extension(asset: InstagramMedia.Asset, uri: Uri): String {
            val path = uri.path.orEmpty().lowercase(Locale.ROOT)
            if (asset.video) return if (path.endsWith(".webm")) ".webm" else ".mp4"
            return listOf(".webp", ".png", ".avif", ".gif").firstOrNull { path.endsWith(it) } ?: ".jpg"
        }
    }
}
