package borg.trikeshed.web

import borg.trikeshed.web.pages.AppDestination
import borg.trikeshed.web.pages.AppDestinations
import kotlinx.browser.document
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.FocusEvent
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.url.URL
import org.w3c.dom.url.URLSearchParams

/** The application navigation bar (application-nav.js), prepended to `body` on pages in the table. */
object AppNav {
    private fun readViews(): dynamic {
        try {
            val value: dynamic = JSON.parse<Any?>(sessionStorage.getItem(AppDestinations.STORAGE_KEY) ?: "{}")
            if (value != null && jsTypeOf(value) == "object" && !(js("Array").isArray(value) as Boolean)) return value
        } catch (_: Throwable) {
        }
        return js("({})")
    }

    fun mount() {
        if (document.getElementById("application-nav") != null) return
        val current = AppDestinations.current(window.location.pathname) ?: return
        var views: dynamic = readViews()

        fun remember() {
            if (URLSearchParams(window.location.search).has("example")) return
            views = readViews()
            views[current.id] = window.location.pathname + window.location.search + window.location.hash
            try { sessionStorage.setItem(AppDestinations.STORAGE_KEY, JSON.stringify(views)) } catch (_: Throwable) {}
        }

        fun destinationHref(d: AppDestination): String {
            if (d.id == "board") return d.href
            val saved: dynamic = views[d.id]
            if (jsTypeOf(saved) == "string") {
                try {
                    val url = URL(saved as String, window.location.origin)
                    if (url.origin == window.location.origin && !url.searchParams.has("example") && AppDestinations.accepts(d, url.pathname)) {
                        return url.pathname + url.search + url.hash
                    }
                } catch (_: Throwable) {
                }
            }
            return d.href
        }

        remember()
        window.addEventListener("pagehide", { remember() })
        window.addEventListener("hashchange", { remember() })
        window.addEventListener("beforeunload", { event: Event ->
            val harness: dynamic = window.asDynamic().Harness
            if (jsTypeOf(harness) != "undefined" && (harness.dirty == true || (harness.drafts?.size ?: 0) as Int > 0)) {
                event.preventDefault()
                event.asDynamic().returnValue = ""
            }
        })

        val nav = document.createElement("nav") as HTMLElement
        nav.id = "application-nav"
        nav.setAttribute("aria-label", "Application")
        val brand = document.createElement("a") as HTMLAnchorElement
        brand.className = "application-brand"
        brand.href = destinationHref(AppDestinations.all[0])
        brand.textContent = "TrikeShed"
        val active = document.createElement("span") as HTMLElement
        active.className = "application-current"
        active.textContent = current.label
        val button = document.createElement("button") as HTMLButtonElement
        button.type = "button"
        button.id = "application-menu"
        button.title = "Navigation menu"
        button.setAttribute("aria-label", "Navigation menu")
        button.setAttribute("aria-expanded", "false")
        button.setAttribute("aria-controls", "application-links")
        val icon = document.createElement("i") as HTMLElement
        icon.setAttribute("data-lucide", "chevron-right")
        icon.setAttribute("aria-hidden", "true")
        button.append(icon)
        val links = document.createElement("div") as HTMLElement
        links.id = "application-links"
        for (d in AppDestinations.all) {
            val link = document.createElement("a") as HTMLAnchorElement
            link.href = destinationHref(d)
            link.textContent = d.label
            link.setAttribute("data-destination", d.id)
            if (d.id == current.id) link.setAttribute("aria-current", "page")
            links.append(link)
        }

        fun close(restoreFocus: Boolean) {
            nav.classList.remove("menu-open")
            button.setAttribute("aria-expanded", "false")
            if (restoreFocus) button.focus()
        }
        button.addEventListener("click", {
            val open = nav.classList.toggle("menu-open")
            button.setAttribute("aria-expanded", open.toString())
        })
        document.addEventListener("keydown", { e: Event ->
            val event = e as KeyboardEvent
            if (event.key == "Escape" && nav.classList.contains("menu-open")) { close(true); event.stopPropagation() }
            if (nav.contains(event.target as? Node)) event.stopPropagation()
        })
        document.addEventListener("pointerdown", { e: Event -> if (!nav.contains(e.target as? Node)) close(false) })
        nav.addEventListener("focusout", { e: Event -> if (!nav.contains((e as FocusEvent).relatedTarget as? Node)) close(false) })
        nav.append(brand, active, button, links)
        document.body!!.prepend(nav)
        callIcons()
        val once: dynamic = js("({once: true})")
        document.addEventListener("DOMContentLoaded", { callIcons() }, once)
        window.addEventListener("pageshow", {
            remember()
            brand.href = destinationHref(AppDestinations.all[0])
            val children = links.children
            for (index in 0 until children.length) (children.item(index) as HTMLAnchorElement).href = destinationHref(AppDestinations.all[index])
            close(false)
        })
    }

    private fun callIcons() {
        val f: dynamic = window.asDynamic().MuxIcons
        if (f != null) f()
    }
}
