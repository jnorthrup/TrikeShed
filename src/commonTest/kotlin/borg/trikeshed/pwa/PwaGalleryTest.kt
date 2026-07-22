/*
 * Copyright (c) TrikeShed Contributors
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 */
package borg.trikeshed.pwa

import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PwaGalleryTest {

    @Test
    fun testEmptyGalleryRendering() {
        val gallery = PwaGallery(0 j { _: Int -> PwaGalleryItem("", "", "") })
        val html = gallery.render()
        assertTrue(html.contains("pwa-gallery"), "Should contain gallery container class")
        assertTrue(html.contains("empty-gallery"), "Should indicate gallery is empty")
    }

    @Test
    fun testPopulatedGalleryRendering() {
        val items = 2 j { index: Int ->
            when (index) {
                0 -> PwaGalleryItem("img1", "https://example.com/1.png", "Image 1")
                1 -> PwaGalleryItem("img2", "https://example.com/2.png", "Image 2")
                else -> error("Out of bounds")
            }
        }
        val gallery = PwaGallery(items)
        val html = gallery.render()
        
        assertTrue(html.contains("pwa-gallery"), "Should contain gallery container")
        assertFalse(html.contains("empty-gallery"), "Should not indicate gallery is empty")
        
        assertTrue(html.contains("https://example.com/1.png"), "Should render first image url")
        assertTrue(html.contains("Image 1"), "Should render first image alt text")
        
        assertTrue(html.contains("https://example.com/2.png"), "Should render second image url")
        assertTrue(html.contains("Image 2"), "Should render second image alt text")
    }

    @Test
    fun testGalleryNavigationAndResponsiveLayout() {
        val items = 3 j { index: Int ->
            PwaGalleryItem("img$index", "https://example.com/$index.png", "Image $index")
        }
        val gallery = PwaGallery(items)
        val html = gallery.render()
        
        assertTrue(html.contains("pwa-gallery-grid"), "Should use a responsive grid layout")
        assertTrue(html.contains("pwa-gallery-item"), "Should style items individually")
        assertTrue(html.contains("gallery-nav"), "Should contain navigation controls")
    }
}
