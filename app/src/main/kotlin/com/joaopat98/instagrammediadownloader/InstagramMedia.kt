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
        """/(?:(?:(?!(?:stories|share)/)[\w.]+/)?(p|reel|reels|tv)/([\w-]+)|stories/[\w.]+/(\d+)|share/(?:(?:p|reel|stories)/)?([\w-]+))/?"""
    )
    private val hosts = setOf("instagram.com", "www.instagram.com", "m.instagram.com")
    private const val USER_AGENT = "InstagramMediaDownloader/1.0"

    data class Link(val uri: URI, val kind: String, val id: String)
    data class Asset(val url: String, val video: Boolean)
    class Unavailable : IOException()
    class StoryUnsupported : IOException()
    class AccountRequired : IOException()

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
        val cleanPath = if (kind.isNotEmpty()) "/$kind/$code" else path.trimEnd('/')
        val clean = URI.create("https://www.instagram.com$cleanPath/")
        return when {
            storyId.isNotEmpty() -> Link(clean, "story", storyId)
            shareCode.isNotEmpty() -> Link(clean, "share", shareCode)
            else -> Link(clean, kind, code)
        }
    }

    fun resolve(initialLink: Link): List<Asset> {
        var link = initialLink
        var photoPreview: Asset? = null
        var carousel = false
        for (attempt in 0..5) {
            if (link.kind == "story") throw StoryUnsupported()
            val response = request(link.uri.toString()).execute()
            checkPublicAccess(response.statusCode(), response.header("Location"))
            when (val status = response.statusCode()) {
                in 300..399 -> {
                    val location = response.header("Location")?.let(::parseUri)
                    val redirected = location?.let(link.uri::resolve) ?: break
                    val next = parseLink(redirected) ?: break
                    // Keep Instagram's redirect target, even when its permalink has an owner prefix.
                    link = next.copy(uri = URI.create("https://www.instagram.com" + redirected.path))
                }
                429 -> throw IOException("Instagram is rate limiting requests. Try again later.")
                in 500..599 -> throw IOException("Instagram is temporarily unavailable. Try again later.")
                200 -> {
                    val page = response.parse()
                    checkPublicAccess(response.statusCode(), page = page)
                    val media = readMedia(page, link)
                    if (media.assets.isNotEmpty()) return media.assets
                    carousel = media.carousel
                    if (!carousel) photoPreview = openGraphPhoto(page, link)
                    break
                }
                else -> break
            }
        }
        if (link.kind in setOf("p", "reel", "reels")) {
            val path = if (link.kind == "p") "p" else "reel"
            try {
                val embed = request("https://www.instagram.com/$path/${link.id}/embed/").execute()
                checkPublicAccess(embed.statusCode(), embed.header("Location"))
                if (embed.statusCode() == 200) {
                    val page = embed.parse()
                    checkPublicAccess(embed.statusCode(), page = page)
                    val media = readMedia(page, link)
                    if (media.assets.isNotEmpty() && (!carousel || media.carousel)) return media.assets
                    carousel = carousel || media.carousel
                    if (!carousel && link.kind == "p") {
                        extractEmbeddedPhoto(page, link)?.let { return listOf(it) }
                    }
                }
            } catch (error: AccountRequired) {
                throw error
            } catch (error: IOException) {
                if (photoPreview == null) throw error
            }
        }
        if (!carousel) photoPreview?.let { return listOf(it) }
        throw Unavailable()
    }

    internal fun checkPublicAccess(status: Int, location: String? = null, page: Document? = null) {
        fun isAccountPath(url: String?): Boolean {
            val path = url?.let(::parseUri)?.path?.trimEnd('/') ?: return false
            return listOf("/accounts/login", "/challenge", "/checkpoint")
                .any { path == it || path.startsWith("$it/") }
        }
        if (status == 401 || status == 403 || isAccountPath(location) ||
            page?.select("form[action], link[rel=canonical][href], meta[property=og:url][content]")?.any {
                isAccountPath(it.attr("action").ifEmpty { it.attr("href").ifEmpty { it.attr("content") } })
            } == true) throw AccountRequired()
    }

    private fun request(url: String) = Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .timeout(12_000)
        .maxBodySize(12 * 1024 * 1024)
        .followRedirects(false)
        .ignoreHttpErrors(true)

    fun extractEmbeddedPhoto(page: Document, link: Link): Asset? = page
        .select(".Embed[data-media-type=GraphImage][data-permalink]")
        .firstNotNullOfOrNull { embed ->
            if (findLink(embed.attr("data-permalink"))?.id != link.id) return@firstNotNullOfOrNull null
            asset(embed.select("img.EmbeddedMediaImage[src]").attr("src"), false)
        }

    fun extract(page: Document, link: Link, includePhotoPreview: Boolean = true): List<Asset> {
        val media = readMedia(page, link)
        if (media.assets.isNotEmpty() || media.carousel || !includePhotoPreview) return media.assets
        return listOfNotNull(openGraphPhoto(page, link))
    }

    private data class PageMedia(val assets: List<Asset>, val carousel: Boolean = false)

    private fun readMedia(page: Document, link: Link): PageMedia {
        val candidates = page.select("script")
            .asSequence()
            .flatMap { script ->
                val body = script.data()
                val data = when {
                    script.attr("type") in setOf("application/json", "application/ld+json") -> parseJson(body)
                    body.contains("\"PolarisEmbedSimple\"") && body.contains("\"contextJSON\"") ->
                        parseJson(body.substringAfter("s.handle(", ""))
                    else -> null
                }
                matchingPosts(data, link, 0)
            }
            .toList()
        val carousels = candidates.filter(::isCarousel)
        val isCarousel = carousels.isNotEmpty() ||
            page.select(".Embed[data-media-type=GraphSidecar][data-permalink]")
                .any { findLink(it.attr("data-permalink"))?.id == link.id }
        if (isCarousel) {
            val slides = carousels.map(::mediaFrom).maxByOrNull { it.size }.orEmpty()
            return PageMedia(slides, carousel = true)
        }
        val best = candidates.map(::mediaFrom).maxWithOrNull(compareBy({ it.size }, { it.count(Asset::video) }))
        if (!best.isNullOrEmpty()) return PageMedia(best)

        // Open Graph metadata can describe a preview or login page.
        if (!matchesCanonical(page, link)) return PageMedia(emptyList())

        val video = sequenceOf("og:video:secure_url", "og:video:url", "og:video")
            .firstNotNullOfOrNull { asset(meta(page, it), true) }
        return PageMedia(listOfNotNull(video))
    }

    private fun openGraphPhoto(page: Document, link: Link): Asset? {
        if (link.kind != "p" || !matchesCanonical(page, link) || meta(page, "og:type").startsWith("video")) {
            return null
        }
        return sequenceOf("og:image:secure_url", "og:image:url", "og:image")
            .firstNotNullOfOrNull {
                asset(meta(page, it), false)?.takeUnless { preview ->
                    Regex("""(?:[?&])stp=c\d""").containsMatchIn(preview.url)
                }
            }
    }

    private fun matchesCanonical(page: Document, link: Link): Boolean =
        sequenceOf(meta(page, "og:url"), page.select("link[rel=canonical]").attr("href"))
            .any { findLink(it)?.id == link.id }

    private fun meta(page: Document, property: String) = page.select("meta[property='$property']").attr("content")

    private fun parseJson(raw: String): Any? = try {
        JSONTokener(raw).nextValue()
    } catch (_: JSONException) {
        null
    }

    private fun matchingPosts(value: Any?, link: Link, depth: Int): Sequence<JSONObject> = sequence {
        if (depth > 90) return@sequence
        when (value) {
            is JSONObject -> {
                if (matches(value, link)) yield(value)
                for (key in value.keys()) {
                    val child = if (key == "contextJSON") parseJson(value.optString(key)) else value.opt(key)
                    yieldAll(matchingPosts(child, link, depth + 1))
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                yieldAll(matchingPosts(value.opt(index), link, depth + 1))
            }
        }
    }

    private fun matches(item: JSONObject, link: Link): Boolean =
        link.id == item.optString("shortcode") || link.id == item.optString("code")

    private fun isCarousel(item: JSONObject): Boolean = item.optInt("media_type") == 8 ||
        item.optString("__typename") == "GraphSidecar" || item.optJSONObject("edge_sidecar_to_children") != null ||
        item.optJSONArray("carousel_media") != null

    private fun mediaFrom(item: JSONObject): List<Asset> {
        if (!isCarousel(item)) return listOfNotNull(single(item))
        val sidecar = item.optJSONObject("edge_sidecar_to_children")
        val children = sidecar?.optJSONArray("edges") ?: item.optJSONArray("carousel_media") ?: return emptyList()
        val expected = maxOf(item.optInt("carousel_media_count"), sidecar?.optInt("count") ?: 0)
        if (children.length() < expected || sidecar?.optJSONObject("page_info")?.optBoolean("has_next_page") == true) {
            return emptyList()
        }
        return (0 until children.length()).map { index ->
            val child = children.optJSONObject(index) ?: return emptyList()
            val node = if (sidecar == null) child else child.optJSONObject("node") ?: return emptyList()
            single(node) ?: return emptyList()
        }
    }

    private fun single(item: JSONObject): Asset? {
        val video = asset(item.optString("video_url"), true) ?: largest(item.optJSONArray("video_versions"), true)
        if (video != null || item.optBoolean("is_video") || item.optInt("media_type") == 2 ||
            item.optString("__typename") == "GraphVideo") return video

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
