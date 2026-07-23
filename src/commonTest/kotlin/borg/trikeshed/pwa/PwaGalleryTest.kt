/*
 * Copyright (c) TrikeShed Contributors
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 */
package borg.trikeshed.pwa

import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PwaGalleryTest {
    @Test
    fun testGalleryInitialRender() {
        val item1 = GalleryItem("img1", "url1.jpg", "Image 1")
        val item2 = GalleryItem("img2", "url2.jpg", "Image 2")
        val items = 2 j { i: Int -> if (i == 0) item1 else item2 }

        val gallery = PwaGallery(items)
        val html = gallery.renderHtml()

        assertTrue(html.contains("class=\"pwa-gallery\""))
        assertTrue(html.contains("url1.jpg"))
        assertTrue(html.contains("url2.jpg"))
        assertTrue(html.contains("Image 1"))
        assertTrue(html.contains("Image 2"))

        // Grid view should be active initially
        assertTrue(html.contains("class=\"pwa-gallery-grid\""))
        assertFalse(html.contains("class=\"pwa-gallery-viewer\""))
    }

    @Test
    fun testGalleryViewerRender() {
        val item1 = GalleryItem("img1", "url1.jpg", "Image 1")
        val item2 = GalleryItem("img2", "url2.jpg", "Image 2")
        val items = 2 j { i: Int -> if (i == 0) item1 else item2 }

        val gallery = PwaGallery(items)
        gallery.setActiveItem("img2")

        val html = gallery.renderHtml()

        // Viewer should be active
        assertTrue(html.contains("class=\"pwa-gallery-viewer\""))
        assertTrue(html.contains("url2.jpg"))

        // Next/Prev buttons
        assertTrue(html.contains("pwaGalleryNext"))
        assertTrue(html.contains("pwaGalleryPrev"))
        assertTrue(html.contains("pwaGalleryClose"))
    }

    @Test
    fun testGalleryNavigation() {
        val item1 = GalleryItem("img1", "url1.jpg", "Image 1")
        val item2 = GalleryItem("img2", "url2.jpg", "Image 2")
        val item3 = GalleryItem("img3", "url3.jpg", "Image 3")
        val items = 3 j { i: Int ->
            when (i) {
                0 -> item1
                1 -> item2
                else -> item3
            }
        }

        val gallery = PwaGallery(items)

        // Start by viewing img1
        gallery.setActiveItem("img1")
        assertEquals("img1", gallery.getActiveItem()?.id)

        // Go next
        gallery.next()
        assertEquals("img2", gallery.getActiveItem()?.id)

        // Go next again
        gallery.next()
        assertEquals("img3", gallery.getActiveItem()?.id)

        // Go next from last (should wrap around or stop, let's say wrap)
        gallery.next()
        assertEquals("img1", gallery.getActiveItem()?.id)

        // Go prev from first (should wrap)
        gallery.prev()
        assertEquals("img3", gallery.getActiveItem()?.id)

        // Go prev
        gallery.prev()
        assertEquals("img2", gallery.getActiveItem()?.id)

        // Close viewer
        gallery.closeViewer()
        assertEquals(null, gallery.getActiveItem())
    }
}
