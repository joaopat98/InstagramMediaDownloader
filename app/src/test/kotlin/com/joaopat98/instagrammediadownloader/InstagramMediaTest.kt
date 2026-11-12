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

    @Test fun selectsExactStory() {
        val link = requireNotNull(InstagramMedia.findLink("https://instagram.com/stories/example/123/"))
        val page = Jsoup.parse("""
            <script type="application/json">
            {"items":[{"id":"12_1","display_url":"$cdn/wrong.jpg"},
              {"id":"123_1","video_url":"$cdn/story.mp4","is_video":true}]}
            </script>
        """.trimIndent())
        assertEquals(listOf(InstagramMedia.Asset("$cdn/story.mp4", true)), InstagramMedia.extract(page, link))
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
