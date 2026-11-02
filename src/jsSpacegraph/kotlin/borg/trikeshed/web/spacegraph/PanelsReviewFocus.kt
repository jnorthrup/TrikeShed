package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document

/** Temporarily move the live review form above the canvas; never recreate its controls. */
class PanelsReviewFocus(private val panels: Panels) {
    private class Entry(val box: dynamic, val current: () -> Boolean)
    private val entries = mutableListOf<Entry>()
    private val button = document.createElement("button").asDynamic()
    private val dialog = document.createElement("dialog").asDynamic()
    private val content = document.createElement("div").asDynamic()
    private val close = document.createElement("button").asDynamic()
    private var selected: Entry? = null
    private var placeholder: dynamic = null
    private var heldPane: dynamic = null
    private var heldToken = 0
    private var nextToken = 0
    private var trigger: dynamic = null
    private var observer: dynamic = null

    init {
        button.textContent = "Review results"
        button.title = "Open a live review form without closing other workspace panels"
        button.hidden = true
        byId("runBtn").parentNode.insertBefore(button, byId("runBtn"))
        dialog.setAttribute("aria-label", "Review results")
        dialog.style.cssText = "width:min(760px,calc(100vw - 48px));max-width:calc(100vw - 48px);max-height:calc(100vh - 48px);overflow:auto;background:var(--panel,#101620);color:var(--ink,#ddd);border:1px solid var(--line,#263348);border-radius:10px;padding:16px"
        dialog.className = "document-review-result"
        val header = document.createElement("header").asDynamic()
        header.style.cssText = "display:flex;justify-content:space-between;gap:12px;align-items:center"
        val title = document.createElement("strong"); title.textContent = "Review results"; header.appendChild(title)
        close.textContent = "Close review focus"
        header.appendChild(close); dialog.appendChild(header); dialog.appendChild(content); document.body!!.appendChild(dialog)
        on(button, "click", {
            trigger = document.activeElement
            choose()
            if (!truthy(dialog.open)) dialog.showModal()
            close.focus()
        })
        on(close, "click", { dialog.close() })
        on(dialog, "close", { restore(); if (truthy(trigger?.isConnected)) trigger.focus() })
        observer = jsNew(js("MutationObserver"), { _: dynamic, _: dynamic ->
            val active = selected
            if (active != null && (!truthy(placeholder?.isConnected) || !active.current())) {
                restore(); if (truthy(dialog.open)) choose(true)
            } else if (active == null) prune()
        })
        observer.observe(document.body, objOf { it.childList = true; it.subtree = true })
        panels.page.own {
            observer.disconnect(); restore(); if (truthy(dialog.open)) dialog.close()
            dialog.remove(); button.remove(); entries.clear()
        }
    }

    fun register(box: dynamic, current: () -> Boolean) {
        entries.add(Entry(box, current)); button.hidden = false
    }

    /** A detached source is no longer current even before the mutation observer runs. */
    fun sourceConnected(box: dynamic): Boolean =
        if (selected?.box === box) truthy(placeholder?.isConnected) else truthy(box.isConnected)

    private fun holdGeometry(box: dynamic) {
        val pane = box.closest(".result") ?: return
        val width = num(pane.offsetWidth); val height = num(pane.offsetHeight)
        if (width <= 0 || height <= 0) return
        // offset dimensions are in the canvas's unscaled layout units. Keep the source
        // frame scrollable even if the form grows while it is in the dialog.
        heldPane = pane; heldToken = ++nextToken
        pane.setAttribute("data-review-focus-hold", heldToken.toString())
        pane.style.boxSizing = "border-box"
        pane.style.width = "${width}px"; pane.style.height = "${height}px"
    }

    private fun releaseGeometry() {
        val pane = heldPane ?: return
        val token = heldToken.toString()
        heldPane = null
        fun release() { if (pane.getAttribute("data-review-focus-hold") == token) pane.removeAttribute("data-review-focus-hold") }
        if (panels.page.closing) release() else panels.page.frame { panels.page.frame { release() } }
    }

    private fun restore() {
        val active = selected ?: return
        selected = null
        if (truthy(placeholder?.isConnected)) placeholder.replaceWith(active.box) else active.box.remove()
        placeholder = null
        releaseGeometry()
    }

    private fun choose(focus: Boolean = false) {
        restore()
        prune()
        content.textContent = ""
        if (entries.isEmpty()) {
            val empty = document.createElement("p"); empty.textContent = "No current review forms."; content.appendChild(empty)
            if (focus) close.focus()
            return
        }
        var first: dynamic = null
        entries.forEachIndexed { index, entry ->
            val item = document.createElement("button").asDynamic()
            val node = entry.box.closest(".node")
            item.textContent = "Open review " + (index + 1) + if (node == null) "" else " · " + str(node.dataset.id)
            content.appendChild(item)
            if (first == null) first = item
            on(item, "click", {
                if (!truthy(entry.box.isConnected) || !entry.current()) { choose(true); return@on }
                holdGeometry(entry.box)
                placeholder = document.createElement("span").asDynamic()
                placeholder.className = "document-review"; placeholder.hidden = true
                entry.box.parentNode.insertBefore(placeholder, entry.box)
                selected = entry
                content.textContent = ""
                val back = document.createElement("button").asDynamic(); back.textContent = "Choose another review"
                content.appendChild(back); content.appendChild(entry.box)
                on(back, "click", { choose(true) }); back.focus()
            })
        }
        if (focus) first.focus()
    }

    private fun prune() {
        entries.removeAll { !truthy(it.box.isConnected) || !it.current() }
        button.hidden = entries.isEmpty()
    }
}
