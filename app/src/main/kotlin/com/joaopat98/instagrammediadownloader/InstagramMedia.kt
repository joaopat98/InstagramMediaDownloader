package com.joaopat98.instagrammediadownloader

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI
import java.util.Locale

internal object InstagramMedia {
    private val linkPattern = Regex("""https?://[^\s<>"“”]+""", RegexOption.IGNORE_CASE)
    private val trailingPunctuation = Regex("""[).,;!\]]+$""")
    private val mediaPath = Regex(
        """/(?:(p|reel|reels|tv)/([\w-]+)|stories/[\w.]+/(\d+)|share/(?:(?:p|reel|stories)/)?([\w-]+))/?"""
    )
    private val hosts = setOf("instagram.com", "www.instagram.com", "m.instagram.com")
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    data class Link(val uri: URI, val kind: String, val id: String)
    data class Asset(val url: String, val video: Boolean)

    fun findLink(text: String?): Link? = linkPattern.findAll(text.orEmpty()).firstNotNullOfOrNull {
        parseUri(it.value.replace(trailingPunctuation, ""))?.let(::parseLink)
    }

    private fun parseUri(text: String): URI? = try {
        URI.create(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseLink(uri: URI): Link? {
        if (uri.host?.lowercase(Locale.ROOT) !in hosts || uri.rawUserInfo != null || uri.port != -1 ||
            uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null

        val path = uri.path ?: return null
        val (kind, code, storyId, shareCode) = mediaPath.matchEntire(path)?.destructured ?: return null
        val clean = URI.create("https://www.instagram.com" + path.trimEnd('/') + "/")
        return when {
            storyId.isNotEmpty() -> Link(clean, "story", storyId)
            shareCode.isNotEmpty() -> Link(clean, "share", shareCode)
            else -> Link(clean, kind, code)
        }
    }

    fun resolve(initialLink: Link): List<Asset> {
        var link = initialLink
        for (attempt in 0..5) {
            val response = Jsoup.connect(link.uri.toString())
                .userAgent(USER_AGENT)
                .timeout(12_000)
                .maxBodySize(12 * 1024 * 1024)
                .followRedirects(false)
                .ignoreHttpErrors(true)
                .execute()
            when (val status = response.statusCode()) {
                in 300..399 -> {
                    val location = response.header("Location")?.let(::parseUri)
                    link = location?.let(link.uri::resolve)?.let(::parseLink) ?: break
                }
                429 -> throw IOException("Instagram is rate limiting requests. Try again later.")
                in 500..599 -> throw IOException("Instagram is temporarily unavailable. Try again later.")
                200 -> {
                    val assets = extract(response.parse(), link)
                    if (assets.isNotEmpty()) return assets
                    break
                }
                else -> break
            }
        }
        throw IOException("Instagram did not expose a downloadable file. The link may require sign-in, have expired, or be unavailable.")
    }

    fun extract(page: Document, link: Link): List<Asset> {
        val candidates = page.select("script[type=application/json], script[type=application/ld+json]")
            .asSequence()
            .flatMap { script ->
                val data = try {
                    JSONTokener(script.data()).nextValue()
                } catch (_: JSONException) {
                    null
                }
                matchingMedia(data, link, 0)
            }
        val best = candidates.maxWithOrNull(compareBy({ it.size }, { it.count(Asset::video) }))
        if (best != null) return best

        // Open Graph metadata can describe a preview or login page.
        val canonical = meta(page, "og:url").ifEmpty { page.select("link[rel=canonical]").attr("href") }
        val canonicalLink = findLink(canonical)
        if (link.kind == "story" || canonicalLink?.id != link.id) return emptyList()

        val video = sequenceOf("og:video:secure_url", "og:video:url", "og:video")
            .firstNotNullOfOrNull { asset(meta(page, it), true) }
        if (video != null) return listOf(video)
        if (link.kind != "p" || meta(page, "og:type").startsWith("video")) return emptyList()

        val photo = sequenceOf("og:image:secure_url", "og:image:url", "og:image")
            .firstNotNullOfOrNull { asset(meta(page, it), false) }
        return listOfNotNull(photo)
    }

    private fun meta(page: Document, property: String) = page.select("meta[property='$property']").attr("content")

    private fun matchingMedia(value: Any?, link: Link, depth: Int): Sequence<List<Asset>> = sequence {
        if (depth > 90) return@sequence
        when (value) {
            is JSONObject -> {
                if (matches(value, link)) {
                    val items = mediaFrom(value)
                    if (items.isNotEmpty()) yield(items)
                }
                for (key in value.keys()) yieldAll(matchingMedia(value.opt(key), link, depth + 1))
            }
            is JSONArray -> for (index in 0 until value.length()) {
                yieldAll(matchingMedia(value.opt(index), link, depth + 1))
            }
        }
    }

    private fun matches(item: JSONObject, link: Link): Boolean {
        if (link.kind != "story") {
            return link.id == item.optString("shortcode") || link.id == item.optString("code")
        }
        return link.id == item.optString("pk") || link.id == item.optString("id").substringBefore('_')
    }

    private fun mediaFrom(item: JSONObject): List<Asset> {
        val sidecar = item.optJSONObject("edge_sidecar_to_children")
        val children = sidecar?.optJSONArray("edges") ?: item.optJSONArray("carousel_media")
        if (children == null) return listOfNotNull(single(item))
        return children.objects()
            .mapNotNull { if (sidecar == null) it else it.optJSONObject("node") }
            .mapNotNull(::single)
            .distinctBy(Asset::url)
            .toList()
    }

    private fun single(item: JSONObject): Asset? {
        val video = asset(item.optString("video_url"), true) ?: largest(item.optJSONArray("video_versions"), true)
        if (video != null || item.optBoolean("is_video") || item.optInt("media_type") == 2) return video

        val images = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
        return largest(images, false) ?: asset(item.optString("display_url"), false)
            ?: asset(item.optString("display_src"), false)
    }

    private fun largest(options: JSONArray?, video: Boolean): Asset? = options?.objects()
        ?.mapNotNull { option ->
            val file = asset(option.optString("url"), video) ?: return@mapNotNull null
            file to option.optInt("width").toLong() * option.optInt("height")
        }
        ?.maxByOrNull { it.second }?.first

    private fun JSONArray.objects() = (0 until length()).asSequence().mapNotNull(::optJSONObject)

    private fun asset(raw: String, video: Boolean): Asset? {
        val uri = parseUri(raw) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port != -1) return null
        return if (host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net")) Asset(raw, video) else null
    }
}
