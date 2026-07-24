package borg.trikeshed.pwa

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

data class GalleryItem(val id: String, val url: String, val title: String)

class PwaGallery(val items: Series<GalleryItem>, val id: String = "gallery") {

    private var activeItemId: String? = null

    fun setActiveItem(itemId: String) {
        activeItemId = itemId
    }

    fun getActiveItem(): GalleryItem? {
        if (activeItemId == null) return null
        for (i in 0 until items.size) {
            val item = items[i]
            if (item.id == activeItemId) return item
        }
        return null
    }

    fun next() {
        val current = getActiveItem() ?: return
        for (i in 0 until items.size) {
            if (items[i].id == current.id) {
                val nextIndex = if (i + 1 < items.size) i + 1 else 0
                activeItemId = items[nextIndex].id
                return
            }
        }
    }

    fun prev() {
        val current = getActiveItem() ?: return
        for (i in 0 until items.size) {
            if (items[i].id == current.id) {
                val prevIndex = if (i - 1 >= 0) i - 1 else items.size - 1
                activeItemId = items[prevIndex].id
                return
            }
        }
    }

    fun closeViewer() {
        activeItemId = null
    }

    fun renderHtml(): String {
        val sb = StringBuilder()
        sb.append("<div class=\"pwa-gallery\" id=\"$id\">")

        val activeItem = getActiveItem()
        if (activeItem == null) {
            // Render grid
            sb.append("<div class=\"pwa-gallery-grid\">")
            for (i in 0 until items.size) {
                val item = items[i]
                sb.append("<div class=\"pwa-gallery-item\" onclick=\"window.pwaGallerySetActive('${id}', '${item.id}')\">")
                sb.append("<img src=\"${item.url}\" alt=\"${item.title}\" />")
                sb.append("<div class=\"pwa-gallery-item-title\">${item.title}</div>")
                sb.append("</div>")
            }
            sb.append("</div>")
        } else {
            // Render viewer
            sb.append("<div class=\"pwa-gallery-viewer\">")
            sb.append("<button class=\"pwa-gallery-close\" onclick=\"window.pwaGalleryClose('${id}')\">Close</button>")
            sb.append("<img src=\"${activeItem.url}\" alt=\"${activeItem.title}\" class=\"pwa-gallery-viewer-img\" />")
            sb.append("<div class=\"pwa-gallery-viewer-title\">${activeItem.title}</div>")
            sb.append("<div class=\"pwa-gallery-viewer-controls\">")
            sb.append("<button class=\"pwa-gallery-prev\" onclick=\"window.pwaGalleryPrev('${id}')\">Prev</button>")
            sb.append("<button class=\"pwa-gallery-next\" onclick=\"window.pwaGalleryNext('${id}')\">Next</button>")
            sb.append("</div>")
            sb.append("</div>")
        }

        sb.append("</div>")
        return sb.toString()
    }
}
