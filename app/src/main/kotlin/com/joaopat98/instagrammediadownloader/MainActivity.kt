package com.joaopat98.instagrammediadownloader

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
            background = surface(Color.WHITE, Color.rgb(175, 193, 184))
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
            background = surface(green, green)
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.rgb(90, 113, 103), green)
            )
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
            setTextColor(green)
            setOnClickListener {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this@MainActivity, InstagramLoginActivity::class.java), SIGN_IN)
            }
        }
        content.add(signIn, 8)

        content.add(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            setText(R.string.open_downloads)
            setTextColor(green)
            setOnClickListener {
                try {
                    startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                } catch (_: ActivityNotFoundException) {
                    state.update(getString(R.string.open_files))
                }
            }
        }, 12)

        content.requestFocus()
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
        val paper = Color.rgb(245, 248, 244)
        val ink = Color.rgb(24, 45, 38)
        val muted = Color.rgb(66, 90, 80)
        val green = Color.rgb(35, 70, 59)
        val errorColor = Color.rgb(150, 40, 34)
        val worker = Executors.newSingleThreadExecutor()
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
                            "InstagramMediaDownloader/$filename"))
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
