package com.joaopat98.instagrammediadownloader

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramMediaTest {
    private val cdn = "https://scontent.cdninstagram.com"

    @Test fun parsesSharedCaptionAndStripsTracking() {
        val link = InstagramMedia.findLink(
            "Look! https://example.org/ (https://www.instagram.com/reel/ABC-123/?igsh=abc)."
        )
        assertEquals("https://www.instagram.com/reel/ABC-123/", link?.uri.toString())
        assertEquals("ABC-123", link?.id)
    }

    @Test fun rejectsLookalikeHostsAndProfiles() {
        assertNull(InstagramMedia.findLink("https://www.instagram.com.example.org/p/ABC/"))
        assertNull(InstagramMedia.findLink("https://www.instagram.com/profile/"))
    }

    @Test fun acceptsOwnerPrefixedOpenGraphPermalink() {
        val link = requireNotNull(InstagramMedia.findLink("https://instagram.com/p/Post/"))
        val page = Jsoup.parse("""
            <meta property="og:url" content="https://www.instagram.com/creator/p/Post/">
            <meta property="og:image" content="$cdn/photo.jpg">
        """.trimIndent())
        assertEquals(listOf(InstagramMedia.Asset("$cdn/photo.jpg", false)), InstagramMedia.extract(page, link))
        assertTrue(InstagramMedia.extract(page, link, includePhotoPreview = false).isEmpty())
    }

    @Test fun embedPhotoMustMatchTheRequestedPost() {
        val link = requireNotNull(InstagramMedia.findLink("https://instagram.com/p/Post/"))
        val page = Jsoup.parse("""
            <div class="Embed" data-media-type="GraphImage" data-permalink="https://www.instagram.com/creator/p/Wrong/">
              <img class="EmbeddedMediaImage" src="$cdn/wrong.jpg">
            </div>
            <div class="Embed" data-media-type="GraphImage" data-permalink="https://www.instagram.com/creator/p/Post/">
              <img class="EmbeddedMediaImage" src="$cdn/photo.jpg">
            </div>
        """.trimIndent())
        assertEquals(InstagramMedia.Asset("$cdn/photo.jpg", false), InstagramMedia.extractEmbeddedPhoto(page, link))
    }

    @Test fun extractsReelVideoFromSerializedEmbedContext() {
        val link = requireNotNull(InstagramMedia.findLink("https://instagram.com/reel/Reel/"))
        val context = org.json.JSONObject.quote("""
            {"gql_data":{"shortcode_media":{"shortcode":"Reel","is_video":true,"video_url":"$cdn/reel.mp4"}}}
        """.trimIndent())
        val page = Jsoup.parse("""
            <script nonce="fixture">
                requireLazy(["ServerJS"],function(ServerJS){
                    var s=(new ServerJS());
                    s.handle({"require":[["PolarisEmbedSimple","init",[],[{"contextJSON":$context}]]]});
                });
            </script>
        """.trimIndent())
        assertEquals(listOf(InstagramMedia.Asset("$cdn/reel.mp4", true)), InstagramMedia.extract(page, link))
    }

    @Test fun extractsMixedCarouselInOrder() {
        val link = requireNotNull(InstagramMedia.findLink("https://instagram.com/p/Album/"))
        val page = Jsoup.parse("""
            <script type="application/json">
            {"data":[
              {"code":"Unrelated","display_url":"$cdn/wrong.jpg"},
              {"code":"Album","carousel_media":[
                {"display_url":"$cdn/photo.jpg"},
                {"media_type":2,"video_versions":[
                  {"url":"$cdn/small.mp4","width":200,"height":300},
                  {"url":"$cdn/large.mp4","width":800,"height":1200}
                ]}
              ]}
            ]}
            </script>
        """.trimIndent())
        assertEquals(
            listOf(InstagramMedia.Asset("$cdn/photo.jpg", false), InstagramMedia.Asset("$cdn/large.mp4", true)),
            InstagramMedia.extract(page, link)
        )
    }

    @Test(expected = InstagramMedia.StoryUnsupported::class)
    fun rejectsStoryBeforeFetching() {
        val link = requireNotNull(InstagramMedia.findLink("https://instagram.com/stories/example/123/"))
        InstagramMedia.resolve(link)
    }

    @Test fun refusesIncompleteCarouselEvenWithACoverImage() {
        val link = requireNotNull(InstagramMedia.findLink("https://instagram.com/p/Album/"))
        val page = Jsoup.parse("""
            <meta property="og:url" content="https://instagram.com/p/Album/">
            <meta property="og:image" content="$cdn/cover.jpg">
            <script type="application/json">
            {"shortcode":"Album","__typename":"GraphSidecar","edge_sidecar_to_children":{"edges":[
              {"node":{"display_url":"$cdn/photo.jpg"}},
              {"node":{"__typename":"GraphVideo","display_url":"$cdn/video-preview.jpg"}}
            ]}}
            </script>
        """.trimIndent())
        assertTrue(InstagramMedia.extract(page, link).isEmpty())
    }

    @Test(expected = InstagramMedia.AccountRequired::class)
    fun stopsOnLoginRedirect() {
        InstagramMedia.checkPublicAccess(302, "/accounts/login/?next=/p/Example/")
    }

    @Test(expected = InstagramMedia.AccountRequired::class)
    fun stopsOnCheckpointPage() {
        InstagramMedia.checkPublicAccess(200, page = Jsoup.parse("""<form action="/challenge/example/"></form>"""))
    }

    @Test fun permitsPublicPageWithLoginLink() {
        InstagramMedia.checkPublicAccess(200, page = Jsoup.parse("""<a href="/accounts/login/">Sign in</a>"""))
    }

    @Test fun usesMatchingPostMetadataButDoesNotSaveReelThumbnail() {
        val link = requireNotNull(InstagramMedia.findLink("https://instagram.com/p/Post/"))
        val reel = requireNotNull(InstagramMedia.findLink("https://instagram.com/reel/Post/"))
        val page = Jsoup.parse("""
            <meta property="og:url" content="https://www.instagram.com/p/Post/">
            <meta property="og:image" content="$cdn/photo.jpg">
        """.trimIndent())
        assertEquals(listOf(InstagramMedia.Asset("$cdn/photo.jpg", false)), InstagramMedia.extract(page, link))
        assertTrue(InstagramMedia.extract(page, reel).isEmpty())
    }
}
