package com.whatik.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlImporterTest {

    @Test
    fun extractUrl_findsLinkInsideSharedText() {
        assertEquals("https://vm.tiktok.com/ZMabc123/", UrlImporter.extractUrl("Guarda questo! https://vm.tiktok.com/ZMabc123/ 🔥"))
        assertEquals("https://x.y/z", UrlImporter.extractUrl("  https://x.y/z. "))
        assertNull(UrlImporter.extractUrl("nessun link qui"))
    }

    @Test
    fun nameFromUrl_dropsQueryAndExtension() {
        val url = "https://p16-tiktok-dm-sticker-sign-sg.ibyteimg.com/tos-alisg/abc123~tplv-video2sticker-mid.awebp?x-expires=1"
        assertEquals("abc123 tplv-video2sticker-mid", UrlImporter.nameFromUrl(url))
        assertEquals("sticker", UrlImporter.nameFromUrl("https://example.com/"))
    }

    @Test
    fun findImages_unescapesJsonAndRanksStickersFirst() {
        val html = """
            <html><head><meta property="og:image" content="https://cdn.example.com/cover.jpg"></head>
            <body><script>{"sticker":{"url":"https://p16-sign.ibyteimg.com/tos/abc~tplv-video2sticker-mid.awebp?x=1&y=2"}}</script>
            <img src="https://cdn.example.com/photo.png?v=3"> <a href="https://cdn.example.com/page.html">x</a>
            <img src="https://cdn.example.com/photo.png?v=4">
            </body></html>
        """.trimIndent()
        val found = UrlImporter.findImages(html, "https://example.com/p")
        assertEquals(3, found.size)
        assertEquals("https://p16-sign.ibyteimg.com/tos/abc~tplv-video2sticker-mid.awebp?x=1&y=2", found[0].url)
        assertTrue(found[0].looksSticker)
        assertEquals(listOf("https://cdn.example.com/cover.jpg", "https://cdn.example.com/photo.png?v=3"), found.drop(1).map { it.url })
        assertTrue(found.drop(1).none { it.looksSticker })
    }
}
