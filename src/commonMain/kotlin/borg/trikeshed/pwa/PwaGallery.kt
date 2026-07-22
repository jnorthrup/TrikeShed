/*
 * Copyright (c) TrikeShed Contributors
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 */
package borg.trikeshed.pwa

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lcnc.editor.html
import borg.trikeshed.lcnc.editor.HtmlBuilder

class PwaGalleryItem(val id: String, val imageUrl: String, val title: String)

class PwaGallery(private val items: Series<PwaGalleryItem>) {

    fun render(): String = html {
        div(classes = "pwa-gallery") {
            if (items.size == 0) {
                div(classes = "empty-gallery") {
                    text("No items in gallery.")
                }
            } else {
                div(classes = "pwa-gallery-grid") {
                    for (i in 0 until items.size) {
                        val item = items[i]
                        div(classes = "pwa-gallery-item", id = item.id) {
                            text("<img src=\"${item.imageUrl}\" alt=\"${item.title}\" class=\"gallery-img\"/>")
                            div(classes = "gallery-item-title") {
                                text(item.title)
                            }
                        }
                    }
                }
                div(classes = "gallery-nav") {
                    text("<button>Prev</button><button>Next</button>")
                }
            }
        }
    }
}
