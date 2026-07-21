package borg.trikeshed.forge.gallery

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.Element

class GalleryRenderer {
    fun render(containerId: String, items: Array<dynamic>) {
        val container = document.getElementById(containerId) as? HTMLElement ?: return
        container.innerHTML = "" // clear previous

        val grid = document.createElement("div") as HTMLDivElement
        grid.className = "gallery-grid"

        for (i in 0 until items.length) {
            val item = items[i]
            val card = renderCard(item)
            grid.appendChild(card)
        }

        container.appendChild(grid)
    }

    private fun renderCard(item: dynamic): Element {
        val type = item.type as? String ?: "unknown"
        return when (type) {
            "text" -> renderTextCard(item)
            "image" -> renderImageCard(item)
            "kanban" -> renderKanbanCard(item)
            "database_row" -> renderDatabaseRow(item)
            "code" -> renderCodeCard(item)
            else -> renderUnknownCard(item)
        }
    }

    private fun renderTextCard(item: dynamic): Element {
        val el = createBaseCard(item)
        val content = item.content as? String ?: ""
        // Minimal markdown parser
        val htmlContent = content
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("_(.*?)_"), "<i>$1</i>")
            .replace(Regex("`(.*?)`"), "<code>$1</code>")

        val p = document.createElement("div")
        p.innerHTML = htmlContent
        el.appendChild(p)
        return el
    }

    private fun renderImageCard(item: dynamic): Element {
        val el = createBaseCard(item)
        val imgUrl = item.objectUrl as? String ?: ""
        val img = document.createElement("img")
        img.setAttribute("src", imgUrl)
        img.setAttribute("style", "max-width: 100%; height: auto;")
        el.appendChild(img)
        return el
    }

    private fun renderKanbanCard(item: dynamic): Element {
        val el = createBaseCard(item)
        el.setAttribute("draggable", "true")
        el.addEventListener("dragstart", { event ->
            val ev = event.asDynamic()
            ev.dataTransfer.setData("text/plain", item.id as? String ?: "")
        })
        val status = item.status as? String ?: "todo"
        val statusEl = document.createElement("div")
        statusEl.className = "kanban-status"
        statusEl.textContent = "Status: \$status"
        el.appendChild(statusEl)
        return el
    }

    private fun renderDatabaseRow(item: dynamic): Element {
        val el = createBaseCard(item)
        val dbInfo = document.createElement("div")
        dbInfo.className = "db-row-info"
        // In a real app we might iterate over fields
        dbInfo.textContent = "DB Row: \${item.id}"
        el.appendChild(dbInfo)
        return el
    }

    private fun renderCodeCard(item: dynamic): Element {
        val el = createBaseCard(item)
        val content = item.content as? String ?: ""
        // Naive JS syntax highlighting
        val highlighted = content
            .replace("fun ", "<span style='color: #7aa2f7;'>fun </span>")
            .replace("val ", "<span style='color: #7aa2f7;'>val </span>")
            .replace("var ", "<span style='color: #7aa2f7;'>var </span>")
            .replace("class ", "<span style='color: #7aa2f7;'>class </span>")
            .replace(Regex("\"(.*?)\""), "<span style='color: #9ece6a;'>\"$1\"</span>")

        val pre = document.createElement("pre")
        pre.innerHTML = highlighted
        el.appendChild(pre)
        return el
    }

    private fun renderUnknownCard(item: dynamic): Element {
        return createBaseCard(item)
    }

    private fun createBaseCard(item: dynamic): Element {
        val el = document.createElement("div") as HTMLDivElement
        el.className = "gallery-card"
        
        val titleEl = document.createElement("div")
        titleEl.className = "name"
        titleEl.textContent = item.title as? String ?: "Untitled"
        el.appendChild(titleEl)

        val metaEl = document.createElement("div")
        metaEl.className = "meta"
        
        val cid = item.cid as? String
        if (cid != null) {
            val cidSpan = document.createElement("span")
            cidSpan.textContent = "CID: \$cid "
            metaEl.appendChild(cidSpan)
        }

        val lastMod = item.lastModified as? Double
        if (lastMod != null) {
            val dateSpan = document.createElement("span")
            val dateObj = kotlin.js.Date(lastMod)
            dateSpan.textContent = "Mod: \${dateObj.toISOString()}"
            metaEl.appendChild(dateSpan)
        }

        el.appendChild(metaEl)
        
        return el
    }
}
